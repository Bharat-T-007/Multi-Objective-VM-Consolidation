package vmconsolidation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Command-line entry point: read configuration, select a policy and record one run. */
public final class Main {
    public static void main(String[] args) throws Exception {
        run(args, false);
    }

    public static void run(String[] args, boolean baseline) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Config config = Config.load(args);
        String policyName = baseline ? "thr_mmt_pabfd" : "nsga2";
        for (String arg : args) {
            if (arg.startsWith("policy=")) policyName = arg.substring(7);
        }
        SimulationRun.Policy policy = parsePolicy(policyName);
        Path output = outputDirectory(args, config, policy);
        Files.createDirectories(output);
        // A failed or interrupted run must also be protected against accidental overwriting.
        for (String file :
                List.of(
                        "manifest.properties",
                        "summary.csv",
                        "workload.csv",
                        "host_metrics.csv",
                        "zone_metrics.csv",
                        "snapshots.csv",
                        "decisions.csv",
                        "migrations.csv",
                        "pareto_fronts.csv",
                        "decision_checks.csv")) {
            if (Files.exists(output.resolve(file))) {
                throw new IllegalArgumentException(
                        "Run output already exists; choose a new directory: " + output);
            }
        }
        Path manifestPath = output.resolve("manifest.properties");
        String manifest = manifest(config, policy);
        // CREATE_NEW also prevents two processes from starting in the same output directory.
        Files.writeString(manifestPath, manifest, StandardOpenOption.CREATE_NEW);
        try {
            var summary = new SimulationRun(config, output, policy).run();
            String workloadHash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    Files.readAllBytes(
                                                            output.resolve("workload.csv"))));
            Files.writeString(
                    manifestPath,
                    manifest.replace("status=running", "status=complete")
                            + "workload_sha256="
                            + workloadHash
                            + "\n");
            System.out.printf(
                    "%s [%s] seed=%d: %.6f kWh, %.6f kg CO2, %d migrations. Results: %s%n",
                    config.workload(),
                    policy.name().toLowerCase(Locale.ROOT),
                    config.seed(),
                    summary.get("it_energy_kwh"),
                    summary.get("carbon_kg"),
                    summary.get("migrations_completed").intValue(),
                    output);
        } catch (Exception error) {
            Files.writeString(
                    manifestPath,
                    manifest.replace("status=running", "status=failed")
                            + "error="
                            + error.toString().replace('\n', ' ')
                            + "\n");
            throw error;
        }
    }

    private static Path outputDirectory(String[] args, Config config, SimulationRun.Policy policy) {
        String policyFolder =
                switch (policy) {
                    case STATIC -> "static";
                    case THR_MMT_PABFD -> "baseline";
                    case NSGA2 -> "runs";
                };
        String folder = "results/" + policyFolder + "/" + config.workload() + "/" + config.seed();
        for (String arg : args) {
            if (arg.startsWith("output=")) {
                folder = arg.substring(7);
                if (folder.isBlank())
                    throw new IllegalArgumentException("output must name a directory");
            }
        }
        return Path.of(folder).toAbsolutePath();
    }

    private static String manifest(Config config, SimulationRun.Policy policy) throws Exception {
        return "status=running\nconfig="
                + config
                + "\njava="
                + System.getProperty("java.version")
                + "\nsimulator=CloudSim Plus 8.0.0\nworkload_type=synthetic\npolicy="
                + policy.name().toLowerCase(Locale.ROOT)
                + "\nselector="
                + (policy == SimulationRun.Policy.NSGA2 ? "benefit_guard_v5" : "not_applicable")
                + "\n"
                + "cooldown_policy=cluster_headroom_escape_v2\n"
                + "initialization=seeded_random_repair\n"
                + "minimum_environmental_gain="
                + MigrationDecision.MIN_GAIN
                + "\nselection_weights=1.0,1.0,1.5\nenvironmental_horizon_s="
                + PlacementProblem.ENERGY_PLANNING_SECONDS
                + "\noptional_max_cpu_utilization=0.9\nenvironment=synthetic_four_zone\n"
                + "source_sha256="
                + sourceHash()
                + "\n";
    }

    public static SimulationRun.Policy parsePolicy(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "static", "static_random" -> SimulationRun.Policy.STATIC;
            case "thr_mmt_pabfd", "pabfd", "baseline" -> SimulationRun.Policy.THR_MMT_PABFD;
            case "nsga2", "proposed" -> SimulationRun.Policy.NSGA2;
            default ->
                    throw new IllegalArgumentException(
                            "Unknown policy: " + name + "; use static, thr_mmt_pabfd or nsga2");
        };
    }

    private static String sourceHash() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var files = Files.walk(Path.of("src"))) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(file.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
        }
        digest.update(Files.readAllBytes(Path.of("pom.xml")));
        return HexFormat.of().formatHex(digest.digest());
    }
}
