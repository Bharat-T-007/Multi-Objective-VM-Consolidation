package vmconsolidation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class OptimizerTest {
    @Test
    void weightedDistanceAndSlackUseTheSameDefinition() {
        var front = List.of(point(0, 1, 1), point(1, 0, 0), point(.5, .5, .5));
        var distances = Nsga2.idealDistances(front);
        assertEquals(Math.sqrt(2.5), distances.get(front.get(0)), 1e-12);
        assertEquals(1, distances.get(front.get(1)), 1e-12);
        assertEquals(Math.sqrt(.875), distances.get(front.get(2)), 1e-12);
        assertSame(front.get(2), Nsga2.compromise(front, new int[] {0}));
    }

    @Test
    void policyNamesAreExplicitAndUnknownNamesFail() {
        assertEquals(SimulationRun.Policy.STATIC, Main.parsePolicy("static_random"));
        assertEquals(SimulationRun.Policy.THR_MMT_PABFD, Main.parsePolicy("baseline"));
        assertEquals(SimulationRun.Policy.NSGA2, Main.parsePolicy("nsga2"));
        assertThrows(IllegalArgumentException.class, () -> Main.parsePolicy("nfga2"));
        assertThrows(IllegalArgumentException.class, () -> Main.parsePolicy("first_fit"));
    }

    static PlacementProblem selectionProblem(double slack) {
        var cfg = new Config(4, 2, 1200, 60, 300, 12, 8, 11, "low", .8, 0, 0, slack);
        return new PlacementProblem(
                new Scenario(cfg), new double[] {100, 100}, new int[] {2, 2}, 0);
    }

    @Test
    void nearIdealSelectorAccountsForVmSizeAndPreservesZeroSlack() {
        var a = new Nsga2.Candidate(new int[] {3, 2}, new double[] {1, 2, 1});
        var b = new Nsga2.Candidate(new int[] {2, 3}, new double[] {2, 1, 1});
        var front = List.of(b, a);
        assertSame(b, Nsga2.compromise(front, new int[] {2, 2}));
        assertSame(b, Nsga2.compromise(front, selectionProblem(0)));
        assertSame(a, Nsga2.compromise(front, selectionProblem(.05)));
        assertEquals(13.92, Nsga2.migrationSeconds(a.placement, selectionProblem(.05)), 1e-10);
        assertEquals(27.84, Nsga2.migrationSeconds(b.placement, selectionProblem(.05)), 1e-10);
    }

    @Test
    void nearIdealSelectorCannotChooseCheapPointOutsideDistanceBand() {
        var best = new Nsga2.Candidate(new int[] {3, 3}, new double[] {1, 1, 3});
        var stay = new Nsga2.Candidate(new int[] {2, 2}, new double[] {3, 3, 1});
        assertSame(best, Nsga2.compromise(List.of(stay, best), selectionProblem(.05)));
        assertSame(stay, Nsga2.compromise(List.of(stay, best), selectionProblem(.5)));
    }

    @Test
    void selectionSlackRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> selectionProblem(-.1));
        assertThrows(IllegalArgumentException.class, () -> selectionProblem(Double.NaN));
        assertThrows(
                IllegalArgumentException.class, () -> selectionProblem(Double.POSITIVE_INFINITY));
    }

    @Test
    void pressureLiftsLocksOnOtherHostsBeforeOverload() {
        var s = new Scenario(new Config(4, 5, 1200, 60, 300, 12, 8, 11, "low", .8, 0, 1800));
        int[] current = {2, 2, 0, 2, 2};
        double[] demand = {1700, 1500, 1000, 400, 1200};
        assertArrayEquals(
                new boolean[] {true, true, true, true, true},
                PlacementProblem.movementAllowed(s, demand, current, new double[5], 100));
    }

    @Test
    void cooldownLocksRecentMovesButAllowsPressureReliefAndExpiry() {
        var cfg = new Config(4, 2, 1200, 60, 300, 12, 8, 11, "low", .8, 0, 900);
        var s = new Scenario(cfg);
        int[] current = {2, 2};
        double[] last = {0, 0};
        assertArrayEquals(
                new boolean[] {false, false},
                PlacementProblem.movementAllowed(s, new double[] {100, 100}, current, last, 100));
        assertArrayEquals(
                new boolean[] {true, true},
                PlacementProblem.movementAllowed(s, new double[] {100, 100}, current, last, 900));
        assertArrayEquals(
                new boolean[] {true, true},
                PlacementProblem.movementAllowed(s, new double[] {2500, 2000}, current, last, 100));
        var p =
                new PlacementProblem(
                        s, new double[] {100, 100}, current, 100, new boolean[] {false, false});
        assertFalse(p.feasible(new int[] {3, 3}));
        assertArrayEquals(current, p.repair(new int[] {3, 3}, new Random(9)));
        for (var c : Nsga2.search(p, new Random(9))) assertArrayEquals(current, c.placement);
    }

    @Test
    void serialProjectionRejectsBlockedSwapsAndKeepsExecutableMoves() {
        var cfg = new Config(4, 6, 600, 60, 300, 8, 4, 11, "low", .8, 0);
        var s = new Scenario(cfg);
        int[] current = {2, 2, 3, 3, 2, 3};
        var p = new PlacementProblem(s, s.demand(0), current, 0);
        assertTrue(p.feasible(current));
        int[] blocked = {2, 3, 3, 3, 2, 2};
        assertTrue(p.feasible(blocked));
        assertArrayEquals(current, p.executableProjection(blocked));
        int[] reachable = {2, 2, 3, 2, 2, 3};
        assertTrue(p.feasible(reachable));
        assertArrayEquals(reachable, p.executableProjection(reachable));
    }

    static Nsga2.Candidate point(double a, double b, double c) {
        return new Nsga2.Candidate(new int[] {0}, new double[] {a, b, c});
    }

    @Test
    void knownFrontAndDominance() {
        var a = point(1, 3, 2);
        var b = point(2, 1, 1);
        var c = point(3, 2, 3);
        var fronts = Nsga2.rank(List.of(a, b, c));
        assertEquals(Set.of(a, b), new HashSet<>(fronts.getFirst()));
        assertEquals(1, c.rank);
        assertTrue(Nsga2.dominates(b, c));
        assertFalse(Nsga2.dominates(a, b));
    }

    @Test
    void crowdingRetainsExtremesAndHandlesConstantAxes() {
        var a = point(1, 1, 4);
        var b = point(2, 1, 3);
        var c = point(3, 1, 2);
        var d = point(4, 1, 1);
        Nsga2.crowd(List.of(a, b, c, d));
        assertTrue(Double.isInfinite(a.crowding));
        assertTrue(Double.isInfinite(d.crowding));
        assertTrue(Double.isFinite(b.crowding));
        assertTrue(b.crowding > 0);
        var equal = List.of(point(1, 1, 1), point(1, 1, 1), point(1, 1, 1));
        Nsga2.crowd(equal);
        assertTrue(equal.stream().allMatch(p -> p.crowding == 0));
    }

    @Test
    void compromiseHandlesConstantDimensions() {
        var a = new Nsga2.Candidate(new int[] {0}, new double[] {1, 0, 1});
        var b = new Nsga2.Candidate(new int[] {1}, new double[] {1, 0, 1});
        assertSame(a, Nsga2.compromise(List.of(b, a), new int[] {0}));
    }

    @Test
    void repairAndSeededSearchAreFeasibleAndReproducible() {
        Config cfg = new Config(12, 24, 600, 60, 300, 16, 8, 11, "high", .8, .08);
        Scenario scenario = new Scenario(cfg);
        int[] initial = new int[24];
        Arrays.fill(initial, -1);
        var p = new PlacementProblem(scenario, scenario.demand(0), initial, 0);
        initial = p.repair(initial, new Random(1));
        assertNotNull(initial);
        assertTrue(p.feasible(initial));
        p = new PlacementProblem(scenario, scenario.demand(0), initial, 0);
        var a = Nsga2.search(p, new Random(9));
        var b = Nsga2.search(p, new Random(9));
        assertFalse(a.isEmpty());
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertArrayEquals(a.get(i).placement, b.get(i).placement);
            assertTrue(p.feasible(a.get(i).placement));
            for (var other : a) assertFalse(Nsga2.dominates(other, a.get(i)));
        }
    }

    @Test
    void tinyExhaustiveFrontMatchesObjectiveRanking() {
        Config cfg = new Config(4, 2, 600, 60, 300, 20, 8, 11, "medium", .8, 0);
        Scenario s = new Scenario(cfg);
        int[] initial = {2, 3};
        var p = new PlacementProblem(s, s.demand(0), initial, 0);
        var all = new ArrayList<Nsga2.Candidate>();
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                if (p.feasible(new int[] {x, y}))
                    all.add(new Nsga2.Candidate(new int[] {x, y}, p.evaluate(new int[] {x, y})));
        var exact = Nsga2.rank(all).getFirst();
        var found = Nsga2.search(p, new Random(11));
        for (var c : found)
            assertTrue(exact.stream().anyMatch(e -> Arrays.equals(c.placement, e.placement)));
    }
}
