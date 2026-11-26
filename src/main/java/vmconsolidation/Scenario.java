package vmconsolidation;

import java.util.*;

/** Reference hardware plus explicitly synthetic workloads/environmental signals. */
public final class Scenario {
    public record HostSpec(int id, int zone, double peMips, int ram, double[] watts) {
        public double capacity() {
            return 2 * peMips;
        }

        public double power(double utilization) {
            double index = Math.clamp(utilization, 0, 1) * 10;
            int lo = Math.min(9, (int) index);
            return watts[lo] + (watts[lo + 1] - watts[lo]) * (index - lo);
        }
    }

    public record VmSpec(int id, double mips, int ram) {}

    public static final double HOST_BW = 1000,
            VM_BW = 10,
            MIGRATION_FRACTION = 0.5,
            MIGRATION_LOSS = 0.1;
    // Illustrative PUE values for the four logical zones; not regional measurements.
    public static final double[] PUE = {1.10, 1.20, 1.60, 2.00};
    // Synthetic base grid carbon intensities in g/kWh, ordered as ZONES below.
    // Illustrative experimental values, not verified current regional measurements.
    public static final double[] BASE_CI = {90, 220, 440, 720};
    public static final String[] ZONES = {"Oregon", "California", "Virginia", "Dallas"};
    // SPECpower watt curves from Beloglazov & Buyya 2012, Table 5.
    // G4: HP ProLiant ML110 G4 (Intel Xeon 3040, 1860 MHz).
    static final double[] G4 = {86, 89.4, 92.6, 96, 99.5, 102, 106, 108, 112, 114.2, 117};
    // G5: HP ProLiant ML110 G5 (Intel Xeon 3075, 2660 MHz).
    static final double[] G5 = {93.7, 97, 101, 105, 110, 116, 121, 125, 129, 133, 135};
    public final Config config;
    public final List<HostSpec> hosts = new ArrayList<>();
    public final List<VmSpec> vms = new ArrayList<>();
    private final double[][] utilization;
    // Synthetic zone-specific renewable peaks in kW, using the daylight profile below.
    private final double[] zoneRenewablePeakKw;

    public Scenario(Config config) {
        this.config = config;
        int hostsPerZone = config.hosts() / 4;
        for (int j = 0; j < config.hosts(); j++) {
            int zone = j / hostsPerZone;
            // Zones 0-1: G4 lower-MIPS hosts; Zones 2-3: G5 higher-MIPS hosts.
            hosts.add(
                    new HostSpec(
                            j,
                            zone,
                            zone < 2 ? 1860 : 2660,
                            4096,
                            zone < 2 ? G4.clone() : G5.clone()));
        }
        double[] mips = {2500, 2000, 1000, 500};
        int[] ram = {870, 1740, 1740, 613};
        for (int i = 0; i < config.vms(); i++) vms.add(new VmSpec(i, mips[i % 4], ram[i % 4]));

        // Experimental renewable multipliers make the logical zones environmentally heterogeneous.
        double basePeak = config.renewablePeakKw();
        zoneRenewablePeakKw =
                new double[] {basePeak * 5.0, basePeak * 3.5, basePeak * 0.5, basePeak * 0.2};

        int samples = config.duration() / config.sample() + 1;
        utilization = new double[config.vms()][samples];
        Random rng = new Random(config.seed());
        double base =
                switch (config.workload()) {
                    case "low" -> 0.20;
                    case "medium" -> 0.45;
                    default -> 0.72;
                };
        for (int i = 0; i < config.vms(); i++) {
            double offset = 0.06 * rng.nextGaussian(), phase = rng.nextDouble() * 2 * Math.PI;
            double burst = 0;
            for (int k = 0; k < samples; k++) {
                if (rng.nextDouble() < 0.035) burst = 0.18 + 0.16 * rng.nextDouble();
                else burst *= 0.72;
                double time = k * config.sample();
                utilization[i][k] =
                        Math.clamp(
                                base
                                        + offset
                                        + 0.12 * Math.sin(2 * Math.PI * time / 86400)
                                        + 0.04 * Math.sin(2 * Math.PI * time / 3600 + phase)
                                        + 0.025 * rng.nextGaussian()
                                        + burst,
                                0.03,
                                1);
            }
        }
    }

    public double utilization(int vm, double time) {
        int index =
                Math.clamp(
                        (int) Math.floor((time + 1e-7) / config.sample()),
                        0,
                        utilization[vm].length - 1);
        return utilization[vm][index];
    }

    public double[] demand(double time) {
        double[] d = new double[vms.size()];
        for (int i = 0; i < d.length; i++) d[i] = vms.get(i).mips() * utilization(i, time);
        return d;
    }

    /** Time-varying carbon intensity with diurnal pattern and zone phase offset. */
    public static double carbonIntensity(int zone, double time) {
        // A daily sinusoid of amplitude 20%, with a zone-dependent phase.
        return BASE_CI[zone] * (1 + 0.20 * Math.sin(2 * Math.PI * time / 86400 + zone * 0.7));
    }

    /** Zone-specific renewable generation with diurnal solar profile. */
    public double renewableKw(int zone, double time) {
        // Solar profile: active between hours 6-18, peak at noon.
        double hourOfDay = (time / 3600) % 24;
        double solar = Math.max(0, Math.sin(Math.PI * (hourOfDay - 6) / 12));
        return zoneRenewablePeakKw[zone] * solar;
    }

    public static double migrationSeconds(VmSpec vm) {
        // CloudSim Plus uses MB / (Mb/s divided by 8), consistently in decimal units.
        return vm.ram() / (HOST_BW * MIGRATION_FRACTION / 8);
    }

    public static double gridEnergy(double itKwh, double pue, double greenKwh) {
        return Math.max(0, itKwh * pue - greenKwh);
    }
}
