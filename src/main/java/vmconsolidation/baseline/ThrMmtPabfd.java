package vmconsolidation.baseline;

import vmconsolidation.PlacementProblem;
import vmconsolidation.Scenario;

import java.util.*;

/** Beloglazov/Buyya (2012), adapted to this project's demand and reservation model. */
public final class ThrMmtPabfd {
    private ThrMmtPabfd() {}

    public static int[] plan(PlacementProblem problem) {
        State state = new State(problem);
        int hosts = problem.scenario.hosts.size();
        boolean[] excluded = new boolean[hosts],
                receiving = new boolean[hosts],
                tried = new boolean[hosts];
        List<Integer> selected = new ArrayList<>();
        // THR: remove shortest-transfer VMs until every source is below threshold.
        for (int h = 0; h < hosts; h++) {
            double limit =
                    problem.scenario.config.headroom() * problem.scenario.hosts.get(h).capacity();
            if (state.cpu[h] <= limit + 1e-8) continue;
            excluded[h] = true;
            final int source = h;
            var residents = new ArrayList<Integer>();
            for (int i = 0; i < state.placement.length; i++)
                if (state.placement[i] == h && problem.canMove[i]) residents.add(i);
            residents.sort(
                    Comparator.comparingDouble(
                                    (Integer i) ->
                                            Scenario.migrationSeconds(problem.scenario.vms.get(i)))
                            .thenComparingInt(i -> i));
            for (int vm : residents) {
                if (state.cpu[source] <= limit + 1e-8) break;
                state.remove(vm);
                selected.add(vm);
            }
        }
        // PABFD: decreasing requested MIPS, least incremental IT power.
        sortByDemand(selected, problem);
        for (int vm : selected) {
            int destination = state.bestHost(vm, excluded);
            if (destination < 0)
                destination = problem.current[vm]; // Keep every VM if relief is impossible.
            else receiving[destination] = true;
            state.add(vm, destination);
        }
        // Underload: try the least-utilized remaining host; evacuate all or roll back.
        for (int attempt = 0; attempt < hosts; attempt++) {
            int source = -1;
            double lowest = Double.POSITIVE_INFINITY;
            for (int h = 0; h < hosts; h++)
                if (!excluded[h] && !receiving[h] && !tried[h] && state.count[h] > 0) {
                    double utilization = state.cpu[h] / problem.scenario.hosts.get(h).capacity();
                    if (utilization < lowest) {
                        lowest = utilization;
                        source = h;
                    }
                }
            if (source < 0) break;
            tried[source] = true;
            State trial = new State(state);
            boolean[] forbidden = excluded.clone();
            forbidden[source] = true;
            // Evacuation consolidates onto active hosts; it must not wake an empty host.
            for (int h = 0; h < hosts; h++) if (state.count[h] == 0) forbidden[h] = true;
            var residents = new ArrayList<Integer>();
            boolean movable = true;
            for (int i = 0; i < trial.placement.length; i++)
                if (trial.placement[i] == source) {
                    residents.add(i);
                    movable &= problem.canMove[i];
                }
            if (!movable) continue;
            sortByDemand(residents, problem);
            for (int vm : residents) trial.remove(vm);
            boolean success = true;
            for (int vm : residents) {
                int destination = trial.bestHost(vm, forbidden);
                if (destination < 0) {
                    success = false;
                    break;
                }
                trial.add(vm, destination);
            }
            if (success) {
                state = trial;
                excluded[source] = true;
                for (int vm : residents) receiving[state.placement[vm]] = true;
            }
        }
        return state.placement.clone();
    }

    private static void sortByDemand(List<Integer> vms, PlacementProblem problem) {
        vms.sort(
                Comparator.comparingDouble((Integer i) -> problem.demand[i])
                        .reversed()
                        .thenComparingInt(i -> i));
    }

    private static final class State {
        final PlacementProblem problem;
        final int[] placement, ram, count;
        final double[] cpu;

        State(PlacementProblem problem) {
            this.problem = problem;
            placement = new int[problem.current.length];
            Arrays.fill(placement, -1);
            int n = problem.scenario.hosts.size();
            ram = new int[n];
            count = new int[n];
            cpu = new double[n];
            for (int i = 0; i < placement.length; i++) add(i, problem.current[i]);
        }

        State(State other) {
            problem = other.problem;
            placement = other.placement.clone();
            ram = other.ram.clone();
            count = other.count.clone();
            cpu = other.cpu.clone();
        }

        void remove(int vm) {
            int h = placement[vm];
            cpu[h] -= problem.demand[vm];
            ram[h] -= problem.scenario.vms.get(vm).ram();
            count[h]--;
            placement[vm] = -1;
        }

        void add(int vm, int h) {
            placement[vm] = h;
            cpu[h] += problem.demand[vm];
            ram[h] += problem.scenario.vms.get(vm).ram();
            count[h]++;
        }

        int bestHost(int vm, boolean[] excluded) {
            int best = -1;
            double lowest = Double.POSITIVE_INFINITY;
            for (int h = 0; h < cpu.length; h++)
                if (!excluded[h] && problem.fits(vm, h, cpu, ram, count)) {
                    var host = problem.scenario.hosts.get(h);
                    if (cpu[h] + problem.demand[vm]
                            > problem.scenario.config.headroom() * host.capacity() + 1e-8) continue;
                    double before = count[h] == 0 ? 0 : host.power(cpu[h] / host.capacity());
                    double delta =
                            host.power((cpu[h] + problem.demand[vm]) / host.capacity()) - before;
                    if (delta < lowest) {
                        lowest = delta;
                        best = h;
                    }
                }
            return best;
        }
    }
}
