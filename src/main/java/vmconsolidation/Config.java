package vmconsolidation;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Small properties-based configuration; command-line key=value overrides are recorded. */
public record Config(
        int hosts,
        int vms,
        int duration,
        int sample,
        int epoch,
        int population,
        int generations,
        long seed,
        String workload,
        double headroom,
        double renewablePeakKw,
        int cooldown,
        double selectionSlack) {
    public Config(
            int hosts,
            int vms,
            int duration,
            int sample,
            int epoch,
            int population,
            int generations,
            long seed,
            String workload,
            double headroom,
            double renewablePeakKw) {
        this(
                hosts,
                vms,
                duration,
                sample,
                epoch,
                population,
                generations,
                seed,
                workload,
                headroom,
                renewablePeakKw,
                0);
    }

    public Config(
            int hosts,
            int vms,
            int duration,
            int sample,
            int epoch,
            int population,
            int generations,
            long seed,
            String workload,
            double headroom,
            double renewablePeakKw,
            int cooldown) {
        this(
                hosts,
                vms,
                duration,
                sample,
                epoch,
                population,
                generations,
                seed,
                workload,
                headroom,
                renewablePeakKw,
                cooldown,
                0);
    }

    public Config {
        if (hosts < 4
                || hosts % 4 != 0
                || vms < 1
                || duration < 1
                || sample < 1
                || epoch < sample
                || duration % sample != 0
                || epoch % sample != 0
                || population < 4
                || generations < 1
                || !(headroom > 0 && headroom < 1)
                || !Double.isFinite(renewablePeakKw)
                || renewablePeakKw < 0
                || cooldown < 0
                || !Double.isFinite(selectionSlack)
                || selectionSlack < 0
                || selectionSlack > 1
                || !Set.of("low", "medium", "high").contains(workload))
            throw new IllegalArgumentException(
                    "Invalid configuration: hosts must be a positive multiple of 4; durations must"
                        + " align with sample; workload low/medium/high.");
    }

    public static Config load(String[] args) throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(Path.of("config/default.properties"))) {
            p.load(in);
        }
        for (String arg : args) {
            String[] pair = arg.split("=", 2);
            if (pair.length != 2
                    || (!p.containsKey(pair[0])
                            && !pair[0].equals("output")
                            && !pair[0].equals("policy")))
                throw new IllegalArgumentException("Expected known key=value argument, got " + arg);
            if (!pair[0].equals("output")) p.setProperty(pair[0], pair[1]);
        }
        return new Config(
                i(p, "hosts"),
                i(p, "vms"),
                i(p, "duration"),
                i(p, "sample"),
                i(p, "epoch"),
                i(p, "population"),
                i(p, "generations"),
                Long.parseLong(p.getProperty("seed")),
                p.getProperty("workload"),
                Double.parseDouble(p.getProperty("headroom")),
                Double.parseDouble(p.getProperty("renewablePeakKw")),
                i(p, "cooldown"),
                Double.parseDouble(p.getProperty("selectionSlack")));
    }

    private static int i(Properties p, String key) {
        return Integer.parseInt(p.getProperty(key));
    }
}
