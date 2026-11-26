package vmconsolidation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class SimulationTest {
    @TempDir Path temp;

    @Test
    void staticPolicyKeepsTheSharedInitialPlacementAndNeverMigrates() throws Exception {
        Path a = Files.createDirectory(temp.resolve("static")),
                b = Files.createDirectory(temp.resolve("dynamic"));
        var result = new SimulationRun(config(), a, SimulationRun.Policy.STATIC).run();
        new SimulationRun(config(), b, SimulationRun.Policy.THR_MMT_PABFD).run();
        assertEquals(0, result.get("migrations_completed"));
        var snapshots = Files.readAllLines(a.resolve("snapshots.csv"));
        Map<String, String> first = new HashMap<>();
        for (String row : snapshots.subList(1, snapshots.size())) {
            var fields = row.replace("\"", "").split(",");
            assertEquals(first.computeIfAbsent(fields[1], k -> fields[2]), fields[2]);
        }
        assertEquals(
                Files.readString(a.resolve("workload.csv")),
                Files.readString(b.resolve("workload.csv")));
        assertEquals(
                Files.readAllLines(a.resolve("snapshots.csv")).subList(0, 25),
                Files.readAllLines(b.resolve("snapshots.csv")).subList(0, 25));
    }

    Config config() {
        return new Config(12, 24, 1200, 60, 300, 16, 8, 11, "high", .8, .08);
    }

    @Test
    void cooldownIsExercisedInRealSimulationAndRemainsReproducible() throws Exception {
        var cfg = new Config(12, 24, 1200, 60, 300, 16, 8, 11, "low", .8, .08, 900);
        Path a = Files.createDirectory(temp.resolve("cooldown-a")),
                b = Files.createDirectory(temp.resolve("cooldown-b"));
        var first = new SimulationRun(cfg, a).run();
        var second = new SimulationRun(cfg, b).run();
        assertTrue(
                Files.readString(a.resolve("snapshots.csv")).contains("\"false\""),
                "Must exercise a locked VM");
        assertTrue(first.get("migrations_completed") > 0);
        assertEquals(first.get("migrations_started"), first.get("migrations_completed"));
        for (String key : first.keySet())
            if (!key.equals("wall_seconds"))
                assertEquals(first.get(key), second.get(key), 1e-8, key);
        assertEquals(
                Files.readString(a.resolve("snapshots.csv")),
                Files.readString(b.resolve("snapshots.csv")));
    }

    @Test
    void horizonStopsCleanlyWithoutBrokerWarnings() throws Exception {
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var events =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var result =
                    new SimulationRun(config(), Files.createDirectory(temp.resolve("shutdown")))
                            .run();
            assertEquals(1200, result.get("duration_s"));
            assertTrue(
                    result.get("cloudlet_executed_mi") <= result.get("requested_mi"),
                    "Lifecycle cleanup must not add post-horizon cloudlet work to measurements");
            assertEquals(
                    result.get("delivered_mi"),
                    result.get("cloudlet_executed_mi"),
                    result.get("requested_mi") * .001);
            assertTrue(
                    events.list.stream()
                            .noneMatch(
                                    e ->
                                            e.getLevel()
                                                    .isGreaterOrEqual(
                                                            ch.qos.logback.classic.Level.WARN)),
                    () -> events.list.toString());
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void migrationCompletesAcrossDemandChanges() throws Exception {
        Path out = Files.createDirectory(temp.resolve("long"));
        var result =
                new SimulationRun(
                                new Config(12, 24, 9000, 60, 300, 40, 30, 11, "high", .8, .08), out)
                        .run();
        assertEquals(9000, result.get("duration_s"));
        assertEquals(result.get("migrations_started"), result.get("migrations_completed"));
        assertTrue(result.get("cpu_shortfall_fraction") > 0);
    }

    @Test
    void proportionalSchedulerCapsDemandAndAppliesMigrationLoss() throws Exception {
        Path out = Files.createDirectory(temp.resolve("scheduler"));
        var run = new SimulationRun(config(), out);
        try {
            var host = run.hosts.get(8);
            int[] ids = {0, 1, 3, 4};
            for (int i : ids) run.observedDemand[i] = 100;
            for (int i : ids) assertTrue(host.createVm(run.vms.get(i)).fully());
            for (int i : ids) run.observedDemand[i] = run.scenario.vms.get(i).mips();
            double total =
                    Arrays.stream(ids)
                            .mapToDouble(
                                    i ->
                                            host.getVmScheduler()
                                                    .getAllocatedMips(run.vms.get(i))
                                                    .totalMips())
                            .sum();
            assertEquals(5320, total, 1e-8);
            var vm = run.vms.getFirst();
            double before = host.getVmScheduler().getAllocatedMips(vm).totalMips();
            host.addVmMigratingOut(vm);
            assertEquals(before * .9, host.getVmScheduler().getAllocatedMips(vm).totalMips(), 1e-8);
        } finally {
            run.fronts.close();
            run.decisions.close();
            run.migrations.close();
            run.workload.close();
            run.snapshots.close();
            run.checks.close();
            run.metrics.close();
        }
    }

    @Test
    void realSimulationMigratesAndReproducesAccounting() throws Exception {
        Path a = Files.createDirectory(temp.resolve("a")),
                b = Files.createDirectory(temp.resolve("b"));
        var first = new SimulationRun(config(), a).run();
        var second = new SimulationRun(config(), b).run();
        assertEquals(1200, first.get("duration_s"));
        assertTrue(first.get("it_energy_kwh") > 0);
        assertTrue(first.get("carbon_kg") > 0);
        assertTrue(
                first.get("migrations_completed") > 0,
                "Integration must actually exercise migration");
        assertEquals(first.get("migrations_started"), first.get("migrations_completed"));
        assertTrue(first.get("pdm_model") > 0);
        assertTrue(first.get("cpu_shortfall_fraction") >= first.get("pdm_model") * .5);
        assertTrue(first.get("cloudlet_executed_mi") > 0);
        for (String key : first.keySet())
            if (!key.equals("wall_seconds"))
                assertEquals(first.get(key), second.get(key), 1e-8, key);
        for (String file :
                List.of(
                        "workload.csv",
                        "host_metrics.csv",
                        "zone_metrics.csv",
                        "pareto_fronts.csv",
                        "migrations.csv"))
            assertEquals(
                    Files.readString(a.resolve(file)), Files.readString(b.resolve(file)), file);
    }
}
