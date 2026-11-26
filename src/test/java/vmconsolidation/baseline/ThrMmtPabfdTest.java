package vmconsolidation.baseline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vmconsolidation.*;

import java.nio.file.*;

class ThrMmtPabfdTest {
    @TempDir Path temp;

    Scenario scenario(int vms) {
        return new Scenario(new Config(4, vms, 1200, 60, 300, 12, 8, 11, "high", .8, .08));
    }

    void disable(Scenario s, int host) {
        var h = s.hosts.get(host);
        s.hosts.set(host, new Scenario.HostSpec(h.id(), h.zone(), h.peMips(), 0, h.watts()));
    }

    @Test
    void mmtSelectsSmallestVmAndPabfdUsesLowestIncrementalPower() {
        var s = scenario(4);
        var p =
                new PlacementProblem(
                        s, new double[] {2200, 1800, 600, 500}, new int[] {2, 2, 3, 2}, 0);
        assertTrue(p.feasible(p.current));
        int[] target = ThrMmtPabfd.plan(p);
        assertArrayEquals(new int[] {2, 2, 3, 3}, target);
        assertTrue(p.feasible(target));
        assertArrayEquals(target, p.executableProjection(target));
    }

    @Test
    void unsuccessfulEvacuationRollsBackEveryTentativeMove() {
        var s = scenario(3);
        var p = new PlacementProblem(s, new double[] {2500, 1700, 600}, new int[] {2, 3, 3}, 0);
        assertArrayEquals(p.current, ThrMmtPabfd.plan(p));
        assertArrayEquals(new int[] {2, 3, 3}, p.current);
    }

    @Test
    void unplaceableSelectedVmRemainsOnSource() {
        var s = scenario(2);
        disable(s, 3);
        var p = new PlacementProblem(s, new double[] {2500, 2000}, new int[] {2, 2}, 0);
        int[] target = ThrMmtPabfd.plan(p);
        assertArrayEquals(p.current, target);
        assertTrue(p.feasible(target));
    }

    @Test
    void baselineExecutesNativeMigrationsWithSharedAccounting() throws Exception {
        var cfg = new Config(12, 24, 1200, 60, 300, 16, 8, 11, "high", .8, .08);
        var baseline =
                new SimulationRun(cfg, Files.createDirectory(temp.resolve("baseline")), true).run();
        var proposed =
                new SimulationRun(cfg, Files.createDirectory(temp.resolve("proposed"))).run();
        assertEquals(1200, baseline.get("duration_s"));
        assertTrue(baseline.get("it_energy_kwh") > 0);
        assertTrue(baseline.get("carbon_kg") > 0);
        assertTrue(baseline.get("migrations_completed") > 0);
        assertEquals(baseline.get("migrations_started"), baseline.get("migrations_completed"));
        assertEquals(proposed.get("requested_mi"), baseline.get("requested_mi"), 1e-6);
        assertEquals(
                Files.readString(temp.resolve("proposed/workload.csv")),
                Files.readString(temp.resolve("baseline/workload.csv")));
        assertEquals(1, Files.readAllLines(temp.resolve("baseline/pareto_fronts.csv")).size());
        assertTrue(
                Files.readString(temp.resolve("baseline/decisions.csv"))
                        .contains("baseline_target"));
    }
}
