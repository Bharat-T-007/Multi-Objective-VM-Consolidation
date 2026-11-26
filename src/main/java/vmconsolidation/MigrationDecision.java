package vmconsolidation;

import java.util.*;
import java.util.stream.*;

/**
 * Require net environmental benefit when healthy; allow risk relief under actual overload. During
 * overload, prefer candidates that relieve risk without worsening energy (energy-aware relief).
 * Falls back to any risk-reducing candidate only when no energy-safe option exists.
 */
public final class MigrationDecision {
    /** Minimum fractional improvement in energy OR carbon to justify a migration. */
    public static final double MIN_GAIN = 0.003;

    public record Decision(Nsga2.Candidate candidate, boolean apply, String reason) {}

    public static Decision choose(List<Nsga2.Candidate> front, PlacementProblem problem) {
        double[] stay = problem.stayForecast();
        boolean overloaded = !problem.feasible(problem.current);
        var acceptable = new ArrayList<Nsga2.Candidate>();
        for (var candidate : front) {
            if (Nsga2.moves(candidate.placement, problem.current) == 0) continue;
            if (overloaded) {
                if (candidate.objectives[1] < stay[1] - 1e-12) acceptable.add(candidate);
            } else {
                // Leave 10% physical CPU capacity free to avoid repeated pack/relieve cycles.
                boolean safe =
                        problem.peakUtilization(candidate.placement)
                                <= 1 - Scenario.MIGRATION_LOSS + 1e-12;
                // No regression: candidate must not make either energy or carbon worse.
                boolean noRegression =
                        candidate.objectives[0] <= stay[0] + 1e-12
                                && candidate.objectives[2] <= stay[2] + 1e-12;
                // Material improvement: at least MIN_GAIN on energy OR carbon.
                boolean material =
                        (stay[0] > 1e-12 && candidate.objectives[0] <= stay[0] * (1 - MIN_GAIN))
                                || (stay[2] > 1e-12
                                        && candidate.objectives[2] <= stay[2] * (1 - MIN_GAIN));
                if (safe && noRegression && material) acceptable.add(candidate);
            }
        }
        if (acceptable.isEmpty()) {
            var recommendation = Nsga2.compromise(front, problem);
            return new Decision(recommendation, false, "insufficient_net_benefit");
        }
        // During overload, prefer candidates that relieve risk without raising energy.
        List<Nsga2.Candidate> pool = acceptable;
        if (overloaded) {
            var energySafe =
                    acceptable.stream()
                            .filter(c -> c.objectives[0] <= stay[0] + 1e-12)
                            .collect(Collectors.toList());
            if (!energySafe.isEmpty()) pool = energySafe;
        }
        return new Decision(
                Nsga2.compromise(pool, problem), true, overloaded ? "risk_relief" : "net_benefit");
    }
}
