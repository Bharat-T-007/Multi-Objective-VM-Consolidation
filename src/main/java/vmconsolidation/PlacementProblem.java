package vmconsolidation;

import java.util.*;

/** Snapshot of current demand and placement. Inputs are copied; future workload is not exposed. */
public final class PlacementProblem {
    public static final int ENERGY_PLANNING_SECONDS = 3600;
    public final Scenario scenario;
    public final double[] demand;
    public final int[] current;
    public final double time;
    public final boolean[] canMove;

    public PlacementProblem(Scenario scenario, double[] demand, int[] current, double time) {
        this(scenario, demand, current, time, allMovable(current.length));
    }

    public PlacementProblem(
            Scenario scenario, double[] demand, int[] current, double time, boolean[] canMove) {
        this.scenario = scenario;
        this.demand = demand.clone();
        this.current = current.clone();
        this.time = time;
        this.canMove = canMove.clone();
    }

    private static boolean[] allMovable(int n) {
        boolean[] a = new boolean[n];
        Arrays.fill(a, true);
        return a;
    }

    public static boolean[] movementAllowed(
            Scenario scenario, double[] demand, int[] current, double[] lastFinish, double time) {
        double[] cpu = new double[scenario.hosts.size()];
        for (int i = 0; i < current.length; i++) if (current[i] >= 0) cpu[current[i]] += demand[i];
        boolean pressure = false;
        for (int h = 0; h < cpu.length; h++)
            pressure |=
                    cpu[h] > scenario.config.headroom() * scenario.hosts.get(h).capacity() + 1e-8;
        boolean[] allowed = new boolean[current.length];
        for (int i = 0; i < allowed.length; i++)
            allowed[i] =
                    pressure
                            || current[i] < 0
                            || time - lastFinish[i] >= scenario.config.cooldown();
        return allowed;
    }

    public boolean fits(int vm, int host, double[] cpu, int[] ram, int[] count) {
        if (host < 0 || host >= scenario.hosts.size()) return false;
        if (!canMove[vm] && host != current[vm]) return false;
        var h = scenario.hosts.get(host);
        var v = scenario.vms.get(vm);
        return v.mips() <= h.peMips() + 1e-8
                && cpu[host] + demand[vm] <= h.capacity() + 1e-8
                && ram[host] + v.ram() <= h.ram()
                && (count[host] + 1) * Scenario.VM_BW <= Scenario.HOST_BW;
    }

    public boolean feasible(int[] p) {
        if (p.length != demand.length) return false;
        double[] cpu = new double[scenario.hosts.size()];
        int[] ram = new int[cpu.length], count = new int[cpu.length];
        for (int i = 0; i < p.length; i++) {
            if (!fits(i, p[i], cpu, ram, count)) return false;
            cpu[p[i]] += demand[i];
            ram[p[i]] += scenario.vms.get(i).ram();
            count[p[i]]++;
        }
        return true;
    }

    /** Project a target onto the moves our serial controller can actually perform. */
    public int[] executableProjection(int[] target) {
        if (Arrays.stream(current).anyMatch(h -> h < 0)) return target.clone();
        int[] out = current.clone();
        double[] cpu = new double[scenario.hosts.size()];
        int[] ram = new int[cpu.length], count = new int[cpu.length];
        for (int i = 0; i < out.length; i++) {
            cpu[out[i]] += demand[i];
            ram[out[i]] += scenario.vms.get(i).ram();
            count[out[i]]++;
        }
        boolean progress;
        do {
            progress = false;
            for (int i = 0; i < out.length; i++)
                if (out[i] != target[i] && fits(i, target[i], cpu, ram, count)) {
                    int from = out[i], to = target[i], memory = scenario.vms.get(i).ram();
                    cpu[from] -= demand[i];
                    ram[from] -= memory;
                    count[from]--;
                    cpu[to] += demand[i];
                    ram[to] += memory;
                    count[to]++;
                    out[i] = to;
                    progress = true;
                    break;
                }
        } while (progress);
        return feasible(out) ? out : null;
    }

    /** Bounded randomized constructive repair, prioritizing the proposed host. */
    public int[] repair(int[] proposed, Random rng) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int[] out = new int[demand.length];
            var order = new ArrayList<Integer>();
            for (int i = 0; i < out.length; i++) order.add(i);
            Collections.shuffle(order, rng);
            order.sort(
                    Comparator.comparing((Integer i) -> canMove[i])
                            .thenComparing(
                                    Comparator.comparingDouble(
                                                    (Integer i) -> scenario.vms.get(i).mips())
                                            .reversed()));
            double[] cpu = new double[scenario.hosts.size()];
            int[] ram = new int[cpu.length], count = new int[cpu.length];
            boolean ok = true;
            for (int i : order) {
                int chosen = proposed[i];
                if (!fits(i, chosen, cpu, ram, count)) {
                    chosen = -1;
                    int start = rng.nextInt(cpu.length);
                    for (int k = 0; k < cpu.length; k++) {
                        int h = (start + k) % cpu.length;
                        if (fits(i, h, cpu, ram, count)) {
                            chosen = h;
                            break;
                        }
                    }
                }
                if (chosen < 0) {
                    ok = false;
                    break;
                }
                out[i] = chosen;
                cpu[chosen] += demand[i];
                ram[chosen] += scenario.vms.get(i).ram();
                count[chosen]++;
            }
            if (ok) return out;
        }
        return null;
    }

    /** Evaluate a placement: returns [energy, risk, carbon]. */
    public double[] evaluate(int[] p) {
        if (!feasible(p)) throw new IllegalArgumentException("Infeasible candidate");
        double[] cpu = new double[scenario.hosts.size()], zoneEnergy = new double[4];
        int[] count = new int[cpu.length];
        double migrationLoss = 0, totalDemand = Arrays.stream(demand).sum(), risk = 0, energy = 0;
        double horizon = Math.min(scenario.config.epoch(), scenario.config.duration() - time);
        for (int i = 0; i < p.length; i++) {
            cpu[p[i]] += demand[i];
            count[p[i]]++;
            if (current[i] >= 0 && current[i] != p[i])
                migrationLoss +=
                        Scenario.MIGRATION_LOSS
                                * demand[i]
                                * Math.min(horizon, Scenario.migrationSeconds(scenario.vms.get(i)));
        }
        for (int j = 0; j < cpu.length; j++)
            if (count[j] > 0) {
                var host = scenario.hosts.get(j);
                double u = cpu[j] / host.capacity();
                double e = host.power(u) * horizon / 3_600_000;
                energy += e;
                zoneEnergy[host.zone()] += e;
                risk +=
                        Math.pow(
                                        Math.max(0, u - scenario.config.headroom())
                                                / (1 - scenario.config.headroom()),
                                        2)
                                / cpu.length;
            }
        risk += migrationLoss / Math.max(1e-12, totalDemand * horizon);
        double carbon = 0;
        for (int z = 0; z < 4; z++) {
            double greenKwh = scenario.renewableKw(z, time) * horizon / 3600;
            double gridKwh = Scenario.gridEnergy(zoneEnergy[z], Scenario.PUE[z], greenKwh);
            carbon += gridKwh * Scenario.carbonIntensity(z, time) / 1000;
        }
        if (Arrays.stream(current).allMatch(h -> h >= 0)) {
            double[] transition = transitionForecast(p);
            energy = transition[0];
            carbon = transition[1];
        }
        return new double[] {energy, risk, carbon};
    }

    public double peakUtilization(int[] placement) {
        double[] cpu = new double[scenario.hosts.size()];
        double peak = 0;
        for (int i = 0; i < placement.length; i++) cpu[placement[i]] += demand[i];
        for (int h = 0; h < cpu.length; h++)
            peak = Math.max(peak, cpu[h] / scenario.hosts.get(h).capacity());
        return peak;
    }

    public double headroomRisk(int[] placement) {
        double[] cpu = new double[scenario.hosts.size()];
        double risk = 0;
        for (int i = 0; i < placement.length; i++) cpu[placement[i]] += demand[i];
        for (int h = 0; h < cpu.length; h++)
            risk +=
                    Math.pow(
                                    Math.max(
                                                    0,
                                                    cpu[h] / scenario.hosts.get(h).capacity()
                                                            - scenario.config.headroom())
                                            / (1 - scenario.config.headroom()),
                                    2)
                            / cpu.length;
        return risk;
    }

    public double[] stayForecast() {
        double[] values = transitionForecast(current);
        return new double[] {values[0], headroomRisk(current), values[1]};
    }

    /** Current-demand serial-transfer forecast. */
    private double[] transitionForecast(int[] target) {
        int[] placement = current.clone(),
                ram = new int[scenario.hosts.size()],
                count = new int[ram.length];
        double[] cpu = new double[ram.length];
        for (int i = 0; i < placement.length; i++) {
            int h = placement[i];
            cpu[h] += demand[i];
            ram[h] += scenario.vms.get(i).ram();
            count[h]++;
        }
        double horizon = Math.min(ENERGY_PLANNING_SECONDS, scenario.config.duration() - time),
                elapsed = 0,
                energy = 0,
                carbon = 0;
        while (elapsed < horizon - 1e-10) {
            int vm = -1;
            for (int i = 0; i < placement.length; i++)
                if (placement[i] != target[i] && fits(i, target[i], cpu, ram, count)) {
                    vm = i;
                    break;
                }
            int destination = vm < 0 ? -1 : target[vm];
            double transfer =
                    vm < 0 ? horizon - elapsed : Scenario.migrationSeconds(scenario.vms.get(vm));
            double dt = Math.min(transfer, horizon - elapsed);
            double[] zones = new double[4];
            for (int h = 0; h < cpu.length; h++)
                if (count[h] > 0 || h == destination) {
                    var host = scenario.hosts.get(h);
                    double work =
                            cpu[h] + (h == destination ? Scenario.MIGRATION_LOSS * demand[vm] : 0);
                    double e =
                            host.power(Math.min(1, Math.max(0, work / host.capacity())))
                                    * dt
                                    / 3_600_000;
                    energy += e;
                    zones[host.zone()] += e;
                }
            for (int z = 0; z < 4; z++) {
                double greenKwh = scenario.renewableKw(z, time + elapsed) * dt / 3600;
                carbon +=
                        Scenario.gridEnergy(zones[z], Scenario.PUE[z], greenKwh)
                                * Scenario.carbonIntensity(z, time + elapsed)
                                / 1000;
            }
            elapsed += dt;
            if (vm < 0 || dt < transfer - 1e-10) break;
            int source = placement[vm], memory = scenario.vms.get(vm).ram();
            cpu[source] -= demand[vm];
            ram[source] -= memory;
            count[source]--;
            cpu[destination] += demand[vm];
            ram[destination] += memory;
            count[destination]++;
            placement[vm] = destination;
        }
        return new double[] {energy, carbon};
    }
}
