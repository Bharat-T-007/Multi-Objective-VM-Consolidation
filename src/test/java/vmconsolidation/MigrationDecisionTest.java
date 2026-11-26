package vmconsolidation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MigrationDecisionTest {
    @Test
    void forecastAccountsForBothHostsDuringTransfer() {
        var s = new Scenario(new Config(4, 1, 1200, 60, 300, 12, 8, 11, "low", .8, 0));
        var p = new PlacementProblem(s, new double[] {100}, new int[] {2}, 0);
        double seconds = Scenario.migrationSeconds(s.vms.getFirst());
        double stay = s.hosts.get(2).power(100 / 5320.);
        double incoming = s.hosts.get(3).power(10 / 5320.);
        assertEquals(stay * 1200 / 3_600_000, p.stayForecast()[0], 1e-12);
        double expected = (seconds * (stay + incoming) + (1200 - seconds) * stay) / 3_600_000;
        var c = new Nsga2.Candidate(new int[] {3}, p.evaluate(new int[] {3}));
        assertEquals(expected, c.objectives[0], 1e-12);
        assertFalse(MigrationDecision.choose(List.of(c), p).apply());
    }

    @Test
    void pressureSelectsRiskReliefInsteadOfEnvironmentalCompromise() {
        var s = new Scenario(new Config(4, 5, 1200, 60, 300, 12, 8, 11, "high", .8, 0));
        var p =
                new PlacementProblem(
                        s, new double[] {2500, 100, 100, 500, 2500}, new int[] {2, 3, 0, 2, 2}, 0);
        var relief =
                new Nsga2.Candidate(
                        new int[] {3, 3, 0, 2, 2}, p.evaluate(new int[] {3, 3, 0, 2, 2}));
        var stay = new Nsga2.Candidate(p.current, p.stayForecast());
        var choice = MigrationDecision.choose(List.of(stay, relief), p);
        assertTrue(choice.apply());
        assertSame(relief, choice.candidate());
        assertEquals("risk_relief", choice.reason());
    }

    @Test
    void ZeroCarbonDoesNotMakeTinyEnergyChangeMaterial() {
        var s = new Scenario(new Config(4, 2, 86400, 60, 300, 12, 8, 11, "low", .8, 10));
        var p = new PlacementProblem(s, new double[] {100, 100}, new int[] {2, 2}, 43200);
        double[] stay = p.stayForecast();
        assertEquals(0, stay[2]);
        var tiny = new Nsga2.Candidate(new int[] {3, 2}, new double[] {stay[0] * .9999, 0, 0});
        assertFalse(MigrationDecision.choose(List.of(tiny), p).apply());
    }
}
