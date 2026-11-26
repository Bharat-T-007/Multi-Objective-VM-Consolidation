# Multi-objective VM consolidation

A Java 21 / CloudSim Plus 8.0.0 simulation that balances IT energy, operational carbon and CPU delivery. It compares static random placement, THR-MMT/PABFD and a custom NSGA-II consolidation controller.



## Build and run

Requirements: Java 21, Maven, and Python 3.10 or newer for analysis.

```powershell
mvn --batch-mode package
python -m pip install -r requirements.txt
java -jar target/vm-consolidation-1.0.0.jar policy=nsga2 workload=high seed=1011 duration=86400 output=results/my-run
```

Policies: `nsga2`, `thr_mmt_pabfd`, `static`. Workloads: `low`, `medium`, `high`.
Use a fresh output folder for each run. `package` also runs the Java tests.

On Windows, if Java/Maven are not installed, use the supplied local setup:

```powershell
& ./scripts/setup-tools.ps1
& ./scripts/mvn.ps1 -MavenArgs @('--batch-mode', 'package')
& ./.tools/jdk/bin/java.exe -jar target/vm-consolidation-1.0.0.jar policy=nsga2 workload=high seed=1011 duration=86400 output=results/my-run
```

## Run the 24-hour comparison

```powershell
python scripts/run-study.py results/my-study
python scripts/report-study.py results/my-study
```

This runs all three policies on low, medium and high workloads with three seeds
(1011, 2022, 3033): 27 runs of 24 simulated hours each. The analysis writes
`study-report.md`, `aggregate.csv`, `paired-comparison.csv` and figures inside
the selected output folder. Generated results are excluded from Git.

## Code walkthrough

The implementation uses ordinary Java classes, with one responsibility per main module. Read them in this order:

| File under `src/main/java/vmconsolidation/` | Responsibility |
| --- | --- |
| `Main.java` | Reads arguments, selects a policy, protects output folders and records run provenance. |
| `Config.java` | Loads and validates the properties and command-line overrides. |
| `Scenario.java` | Defines host/VM profiles, seeded synthetic demand and synthetic environmental signals. |
| `PlacementProblem.java` | Checks CPU/RAM/bandwidth/nominal-MIPS eligibility, repairs placements and forecasts objectives. |
| `Nsga2.java` | Runs non-dominated sorting, crowding, tournament selection, crossover, mutation and elitist survival. |
| `MigrationDecision.java` | Filters proposed moves for benefit/headroom and selects an acceptable compromise or holds placement. |
| `SimulationRun.java` | Creates CloudSim entities, observes demand, executes serial migrations and measures delivered work and energy. |
| `baseline/ThrMmtPabfd.java` | Implements threshold detection, minimum migration time selection and power-aware placement. |
| `Csv.java` | Writes quoted CSV output. |

`SimulationRun` keeps its CloudSim scheduler, resource reservations, event controller and accounting as nested classes because they share the same simulation state. These extensions support demand-based CPU sharing and source/destination reservations during native migration.

`baseline/BaselineMain.java` and `baseline/StaticMain.java` are convenience entry points. The same three policies are available through the main executable JAR. Static placement is seeded random, not FFD.

## Controller and model

The default scenario has 16 two-core hosts and 32 one-core VMs, a 24-hour horizon, 60-second demand samples and 300-second decisions. Configuration is in `config/default.properties`.

All policies start from the same feasible random placement for a matched workload and seed. NSGA-II represents a placement as one host index per VM. It uses 60 candidates and 50 generations by default. Repair enforces physical constraints, and an execution projection avoids assuming that blocked swaps can happen simultaneously.

The objective order is **predicted energy, performance risk, predicted carbon**. Pareto ranking uses dominance and crowding. A separate compromise selector uses normalized weighted ideal distance with weights `(1, 1, 1.5)`. Within a distance allowance of `0.05`, it prefers shorter total transfers. This is weighted compromise selection, not knee-point selection.

Optional migrations require at least 0.3% predicted energy or carbon gain, no increase in either, and 10% target CPU reserve. Actual overload permits risk relief, preferring energy-safe choices. Cooldown is available but disabled by default. Transfers execute one at a time at 62.5 MB/s with 10% migrating-VM CPU loss.

Host power follows the G4/G5 reference curves. Four **logical zones in one datacenter** have synthetic PUE, carbon-intensity and renewable profiles. Regional names label the scenario; the values are not current grid measurements. The search observes current workload demand, not future samples.

## Outputs and metrics

Each run writes a manifest, a summary, workload/placement snapshots, host and zone interval metrics, migration records and policy decisions. Proposed runs also record predicted Pareto alternatives. In `decision_checks.csv`, `apply=false` means the recommendation was not executed. Baselines have header-only Pareto files.

- `it_energy_kwh`: measured IT energy. Facility/grid energy and operational carbon include the configured PUE and renewable accounting.
- `cpu_shortfall_fraction`: fraction of requested application work that was not delivered. Multiply by 100 for a percentage. It measures CPU work rather than application latency.
- `migrations_completed`: number of completed VM transfers. One accepted plan can contain multiple transfers.
- `slatah`, `pdm_model`, `slav`, `esv`: supplementary model-based performance metrics. The primary performance measure is CPU shortfall.

Predicted performance risk and epoch Pareto objectives are distinct from full-day measured outcomes.

## Matched study and figures

```powershell
# Three policies, three workloads, seeds 1011/2022/3033:
python scripts/run-study.py results/my-study
python scripts/report-study.py results/my-study

# Redraw existing figures without running simulations:
python scripts/generate_all_figures.py results/my-study
```

`run-study.py` records the configuration and source/JAR hashes. `report-study.py` independently checks the recorded accounting, constraints, migrations, matching inputs and provenance before writing tables, figures and `study-report.md` **inside that study folder**. Existing simulation measurements are preserved.

Historical runs retain their recorded source/JAR identities. Rebuilding changes the JAR hash, and source cleanup changes the source hash. The analyzer discloses differences from the current build while still verifying each run against its own batch protocol. Evaluate a changed algorithm with a new matched study.

Dependency attribution is in `THIRD_PARTY_NOTICES.md`. Reports, presentations and saved experiment batches are not included in this source repository.

Tests are under `src/test/java/` and cover ranking, crowding, feasibility/repair, reproducibility, migration accounting, policy selection and simulation integration. `.tools/`, `.m2/` and `target/` contain local tools, downloaded dependencies and generated build files; they are not the implementation.
