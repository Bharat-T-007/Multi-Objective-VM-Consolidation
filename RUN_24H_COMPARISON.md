# Run the 24-hour comparison

Open a terminal in the project folder. Build first:

```powershell
mvn --batch-mode package
python -m pip install -r requirements.txt
python scripts/run-study.py results/my-24h-study-1
python scripts/report-study.py results/my-24h-study-1
```

With the supplied Windows local tools, replace the Maven command with:

```powershell
& ./scripts/setup-tools.ps1
& ./scripts/mvn.ps1 -MavenArgs @('--batch-mode', 'package')
```

Use your installed Python interpreter for `python`. If needed on this computer,
select the Codex bundled interpreter and call it with `& $pythonExe` instead.

This executes 27 matched runs: three policies, three workloads and three seeds,
with 24 simulated hours per run. Use a new output folder for each batch.

Open `results/my-24h-study-1/study-report.md` for comparison tables.
CSV tables and figures are written into the same study folder.
