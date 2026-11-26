package vmconsolidation;

import java.util.*;

/** NSGA-II placement search: feasibility repair, rank/crowding tournaments and elitist survival. */
public final class Nsga2 {
    public static final class Candidate {
        public final int[] placement;
        public final double[] objectives;
        int rank;
        double crowding;

        public Candidate(int[] p, double[] f) {
            placement = p.clone();
            objectives = f.clone();
        }
    }

    public static boolean dominates(Candidate a, Candidate b) {
        boolean strict = false;
        for (int k = 0; k < a.objectives.length; k++) {
            if (a.objectives[k] > b.objectives[k]) return false;
            strict |= a.objectives[k] < b.objectives[k];
        }
        return strict;
    }

    /** Assign Pareto ranks and crowding distances, returning fronts in rank order. */
    public static List<List<Candidate>> rank(List<Candidate> pop) {
        List<List<Candidate>> fronts = new ArrayList<>();
        List<List<Integer>> beats = new ArrayList<>();
        int[] beaten = new int[pop.size()];
        List<Integer> first = new ArrayList<>();
        for (int i = 0; i < pop.size(); i++) {
            var targets = new ArrayList<Integer>();
            beats.add(targets);
            for (int j = 0; j < pop.size(); j++)
                if (i != j) {
                    if (dominates(pop.get(i), pop.get(j))) targets.add(j);
                    else if (dominates(pop.get(j), pop.get(i))) beaten[i]++;
                }
            if (beaten[i] == 0) first.add(i);
        }
        int r = 0;
        while (!first.isEmpty()) {
            var front = new ArrayList<Candidate>();
            var next = new ArrayList<Integer>();
            for (int i : first) {
                pop.get(i).rank = r;
                front.add(pop.get(i));
                for (int j : beats.get(i)) if (--beaten[j] == 0) next.add(j);
            }
            crowd(front);
            fronts.add(front);
            first = next;
            r++;
        }
        return fronts;
    }

    /** Normalized objective-space diversity; boundary solutions have infinite distance. */
    static void crowd(List<Candidate> f) {
        f.forEach(c -> c.crowding = 0);
        if (f.size() <= 2) {
            f.forEach(c -> c.crowding = Double.POSITIVE_INFINITY);
            return;
        }
        for (int k = 0; k < 3; k++) {
            final int objective = k;
            var sorted = new ArrayList<>(f);
            sorted.sort(Comparator.comparingDouble(c -> c.objectives[objective]));
            double range = sorted.getLast().objectives[k] - sorted.getFirst().objectives[k];
            if (range <= 1e-15) continue;
            sorted.getFirst().crowding = sorted.getLast().crowding = Double.POSITIVE_INFINITY;
            for (int i = 1; i < sorted.size() - 1; i++)
                sorted.get(i).crowding +=
                        (sorted.get(i + 1).objectives[k] - sorted.get(i - 1).objectives[k]) / range;
        }
    }

    static final Comparator<Candidate> ORDER =
            Comparator.comparingInt((Candidate c) -> c.rank)
                    .thenComparing(
                            Comparator.comparingDouble((Candidate c) -> c.crowding).reversed());

    public static List<Candidate> search(PlacementProblem problem, Random rng) {
        int size = problem.scenario.config.population(), n = problem.current.length;
        int nhosts = problem.scenario.hosts.size();
        List<Candidate> population = new ArrayList<>();
        if (problem.feasible(problem.current))
            population.add(new Candidate(problem.current, problem.evaluate(problem.current)));

        // Seed with zone-biased placements: prefer clean zones (0, 1) for carbon reduction.
        for (int bias = 0; bias < 4 && population.size() < size; bias++) {
            int[] p = new int[n];
            int cleanStart = bias < 2 ? 0 : nhosts / 2; // Bias toward zone 0-1 or zone 2-3.
            for (int i = 0; i < n; i++) {
                int base = cleanStart + rng.nextInt(nhosts / 2);
                p[i] = base % nhosts;
            }
            p = problem.repair(p, rng);
            if (p != null) p = problem.executableProjection(p);
            if (p != null) population.add(new Candidate(p, problem.evaluate(p)));
        }

        // Seed with consolidation-biased placements: pack onto fewer hosts.
        for (int trial = 0; trial < 3 && population.size() < size; trial++) {
            int[] p = new int[n];
            int activeHosts = nhosts / 2 + rng.nextInt(nhosts / 4); // Use subset of hosts.
            for (int i = 0; i < n; i++) p[i] = rng.nextInt(activeHosts);
            p = problem.repair(p, rng);
            if (p != null) p = problem.executableProjection(p);
            if (p != null) population.add(new Candidate(p, problem.evaluate(p)));
        }

        // Fill remaining with broader random exploration.
        for (int tries = 0; population.size() < size && tries < size * 30; tries++) {
            int[] p = problem.current.clone();
            for (int i = 0; i < n; i++)
                if (p[i] < 0 || rng.nextDouble() < 0.40) p[i] = rng.nextInt(nhosts);
            p = problem.repair(p, rng);
            if (p != null) p = problem.executableProjection(p);
            if (p != null) population.add(new Candidate(p, problem.evaluate(p)));
        }
        if (population.isEmpty()) return List.of();
        while (population.size() < size)
            population.add(
                    new Candidate(
                            population.getFirst().placement, population.getFirst().objectives));
        rank(population);
        for (int gen = 0; gen < problem.scenario.config.generations(); gen++) {
            List<Candidate> combined = new ArrayList<>(population);
            for (int child = 0; child < size; child++) {
                Candidate a = tournament(population, rng), b = tournament(population, rng);
                int[] p = a.placement.clone();
                boolean cross = rng.nextDouble() < 0.9;
                for (int i = 0; i < n; i++) {
                    if (cross && rng.nextBoolean()) p[i] = b.placement[i];
                    // Per-VM mutation probability: 2/n.
                    if (rng.nextDouble() < 2.0 / n) p[i] = rng.nextInt(nhosts);
                }
                p = problem.repair(p, rng);
                if (p != null) p = problem.executableProjection(p);
                if (p != null) combined.add(new Candidate(p, problem.evaluate(p)));
            }
            population = new ArrayList<>();
            for (var front : rank(combined)) {
                front.sort(ORDER);
                for (var c : front) if (population.size() < size) population.add(c);
                if (population.size() == size) break;
            }
        }
        var front = rank(population).getFirst();
        var unique = new LinkedHashMap<String, Candidate>();
        for (var c : front) unique.putIfAbsent(Arrays.toString(c.placement), c);
        return new ArrayList<>(unique.values());
    }

    static Candidate tournament(List<Candidate> pop, Random rng) {
        Candidate a = pop.get(rng.nextInt(pop.size())), b = pop.get(rng.nextInt(pop.size()));
        return ORDER.compare(a, b) <= 0 ? a : b;
    }

    public static Candidate compromise(List<Candidate> front, int[] current) {
        if (front.isEmpty()) throw new IllegalArgumentException("Empty front");
        var distances = idealDistances(front);
        return front.stream()
                .min(
                        Comparator.comparingDouble((Candidate c) -> distances.get(c))
                                .thenComparingInt(c -> moves(c.placement, current))
                                .thenComparing(c -> Arrays.toString(c.placement)))
                .orElseThrow();
    }

    /** Prefer shorter serial transfers inside a bounded band around the ideal-distance best. */
    public static Candidate compromise(List<Candidate> front, PlacementProblem problem) {
        var best = compromise(front, problem.current);
        double slack = problem.scenario.config.selectionSlack();
        if (slack == 0) return best;
        var distances = idealDistances(front);
        double limit = distances.get(best) + slack;
        return front.stream()
                .filter(c -> distances.get(c) <= limit + 1e-12)
                .min(
                        Comparator.comparingDouble(
                                        (Candidate c) -> migrationSeconds(c.placement, problem))
                                .thenComparingInt(c -> moves(c.placement, problem.current))
                                .thenComparingDouble(distances::get)
                                .thenComparing(c -> Arrays.toString(c.placement)))
                .orElseThrow();
    }

    /** Post-search compromise score; Pareto ranking itself does not use these weights. */
    static Map<Candidate, Double> idealDistances(List<Candidate> front) {
        double[] lo = {
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY
        };
        double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (var c : front)
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], c.objectives[k]);
                hi[k] = Math.max(hi[k], c.objectives[k]);
            }
        Map<Candidate, Double> distances = new IdentityHashMap<>();
        for (var c : front) {
            double d = 0;
            for (int k = 0; k < 3; k++)
                if (hi[k] > lo[k])
                    d +=
                            (k == 2 ? 1.5 : 1.0)
                                    * Math.pow((c.objectives[k] - lo[k]) / (hi[k] - lo[k]), 2);
            distances.put(c, Math.sqrt(d));
        }
        return distances;
    }

    static double migrationSeconds(int[] placement, PlacementProblem problem) {
        double total = 0;
        for (int i = 0; i < placement.length; i++)
            if (problem.current[i] >= 0 && placement[i] != problem.current[i])
                total += Scenario.migrationSeconds(problem.scenario.vms.get(i));
        return total;
    }

    public static int moves(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) n++;
        return n;
    }
}
