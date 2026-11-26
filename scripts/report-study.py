"""Validate the complete study, then derive tables, figures and a formal report."""
from pathlib import Path
import csv
import hashlib
import json
import re
import statistics as st
import sys
from collections import defaultdict

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from analyze import validate,read_csv,np

LOADS=('low','medium','high')
POLICIES=('static','baseline','proposed')
LABELS={'static':'Static random / no migration','baseline':'THR-MMT/PABFD','proposed':'NSGA-II'}
METRICS=('it_energy_kwh','facility_energy_kwh','grid_energy_kwh','carbon_kg','cpu_shortfall_fraction',
         'slatah','pdm_model','slav','esv','migrations_completed','migration_seconds','mean_active_hosts','blocked_target_moves','wall_seconds')

def write_csv(path,rows):
    if not rows:return
    with path.open('w',encoding='utf-8',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)

def source_hash():
    h=hashlib.sha256()
    for p in sorted((ROOT/'src').rglob('*')):
        if p.is_file():h.update(p.relative_to(ROOT).as_posix().encode());h.update(p.read_bytes())
    h.update((ROOT/'pom.xml').read_bytes());return h.hexdigest()

def main():
    batch=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/'results/verified-study'
    protocol=json.loads((batch/'protocol.json').read_text());seeds=protocol['seeds'];cfg=protocol['configuration']
    analysis_source_hash=source_hash()
    jar=ROOT/'target/vm-consolidation-1.0.0.jar'
    current_jar_hash=hashlib.sha256(jar.read_bytes()).hexdigest() if jar.exists() else None
    if analysis_source_hash!=protocol['source_sha256'] or current_jar_hash!=protocol['jar_sha256']:
        print('Analyzing a recorded batch from a different source/build. '
              'Run manifests must still match the batch protocol; these results are not attributed to the current executable.',flush=True)
    data={};reports=[];epoch_rows=[];front_stats=[];pairs=[]
    print('Independently checking measured accounting, placement, transfer history and decisions...',flush=True)
    for load in LOADS:
        for seed in seeds:
            initial=[];workloads=[]
            for policy in POLICIES:
                run=batch/policy/load/str(seed);manifest=(run/'manifest.properties').read_text()
                props=dict(line.split('=',1) for line in manifest.splitlines() if '=' in line)
                if props.get('policy')!=protocol['policies'][policy]:raise ValueError('Incorrect policy')
                if props.get('source_sha256')!=protocol['source_sha256']:raise ValueError('Source hash mismatch')
                if props.get('initialization')!='seeded_random_repair':raise ValueError('Unexpected initialization')
                actual=dict(re.findall(r'(\w+)=([^,\]\n]+)',props['config']))
                for key,value in cfg.items():
                    if key in ('workload','seed'):continue
                    if float(actual[key])!=float(value):raise ValueError('Configuration mismatch: '+key)
                if actual['workload']!=load or int(actual['seed'])!=seed:raise ValueError('Run label mismatch')
                summary,fronts,hosts,report=validate(run);data[policy,load,seed]=summary;reports.append(report)
                if policy=='static' and (summary['migrations_completed']!=0 or fronts):raise ValueError('Static policy migrated or searched')
                if policy=='baseline' and fronts:raise ValueError('Heuristic has a Pareto front')
                if policy=='proposed' and (not fronts or summary['empty_fronts']!=0):raise ValueError('Proposed search incomplete')
                if re.search(r'WARN|ERROR|Exception',(run/'console.log').read_text()):raise ValueError('Simulation emitted warning/error')
                initial.append({r['vm']:r['host'] for r in read_csv(run/'snapshots.csv') if float(r['time_s'])==0})
                workloads.append(props['workload_sha256'])
                if policy=='proposed':
                    actions={float(r['time_s']):r for r in read_csv(run/'decision_checks.csv')}
                    for t,front in fronts.items():
                        points=np.array([[float(r[k]) for k in ('energy_kwh','performance_risk','carbon_kg')] for r in front])
                        conflict=any(np.any((points[:,0]>p[0]) & (points[:,2]<p[2])) for p in points)
                        front_stats.append({'workload':load,'seed':seed,'time_s':t,'placements':len(front),
                            'unique_objective_triples':len(set(map(tuple,points))), 'energy_carbon_conflict':bool(conflict),
                            'applied':actions[t]['apply']=='true','reason':actions[t]['reason']})
                        picks={'min_energy':int(np.argmin(points[:,0])),'min_risk':int(np.argmin(points[:,1])),
                               'min_carbon':int(np.argmin(points[:,2])),'recommendation':next(i for i,r in enumerate(front) if r['selected']=='true')}
                        for name,index in picks.items():
                            r=front[index]
                            epoch_rows.append({'workload':load,'seed':seed,'time_s':t,'alternative':name,
                                'energy_kwh':float(r['energy_kwh']),'performance_risk':float(r['performance_risk']),
                                'carbon_kg':float(r['carbon_kg']),'planned_migrations':int(r['planned_migrations']),
                                'recommendation_applied':actions[t]['apply']=='true','placement':r['placement']})
                print(f'Passed {policy}/{load}/{seed}',flush=True)
                del fronts,hosts
            if len(set(workloads))!=1 or not(initial[0]==initial[1]==initial[2]) or len(initial[0])!=int(cfg['vms']):raise ValueError('Unmatched workload or initial placement')
            for reference in ('static','baseline'):
                for metric in METRICS:
                    a,b=data[reference,load,seed][metric],data['proposed',load,seed][metric]
                    pairs.append({'workload':load,'seed':seed,'reference':reference,'metric':metric,'reference_value':a,'proposed_value':b,'difference':b-a,'change_percent':100*(b/a-1) if a else None})
    aggregate=[]
    for load in LOADS:
        for policy in POLICIES:
            for metric in METRICS:
                values=[data[policy,load,s][metric] for s in seeds]
                aggregate.append({'workload':load,'policy':policy,'metric':metric,'n':len(values),'mean':st.mean(values),'sample_sd':st.stdev(values),'minimum':min(values),'maximum':max(values)})
    figures=batch/'figures';figures.mkdir(exist_ok=True)
    write_csv(batch/'aggregate.csv',aggregate);write_csv(batch/'paired-comparison.csv',pairs)
    write_csv(batch/'pareto-alternatives.csv',epoch_rows);write_csv(batch/'pareto-epochs.csv',front_stats)
    validation={'status':'passed','runs':reports,'matched_three_way_pairs':len(seeds)*3,
        'source_sha256':protocol['source_sha256'],'jar_sha256':protocol['jar_sha256'],
        'analysis_source_sha256':analysis_source_hash,
        'note':'Checks use the current independent validator and manifest-recorded threshold. Descriptive analysis; no p-values or global convergence claim.'}
    (batch/'validation.json').write_text(json.dumps(validation,indent=2)+'\n')
    def values(policy,load,key,scale=1):return [data[policy,load,s][key]*scale for s in seeds]
    from presentation_figures import main as render_figures
    render_figures()
    # Report and manuscript use the same measured tables.
    text=f'''# Multi-objective VM consolidation: verified results

## Experimental design

CloudSim Plus 8.0.0, Java 21. {cfg['hosts']} two-core hosts and {cfg['vms']} persistent one-core VMs; {cfg['duration']} measured seconds per run. Workload demand is synthetic and changes every {cfg['sample']} seconds. Decisions occur every {cfg['epoch']} seconds. Population {cfg['population']}, generations {cfg['generations']}. Seeds: {', '.join(map(str,seeds))}. All three methods begin with the same seeded random feasible placement for a workload/seed pair. Static random placement performs no migrations; it is not First-Fit Decreasing. THR-MMT/PABFD is an adaptation to this scenario, not a numerical reproduction of its source paper.

All {len(reports)} runs passed independent accounting, transfer, constraint and decision checks. All {3*len(seeds)} sets have matching workload traces and initial placements. One frozen source/JAR was used. Configurations and source/JAR hashes are in protocol.json and validation.json. No tuning was performed on these new seed outcomes. Means and sample standard deviations are descriptive; no statistical-significance or global-optimality claim is made.

## Measured outcomes

CPU shortfall is a requested CPU-time loss percentage, not application latency or a contractual SLA-violation rate. Raw CSV values are fractions. Values below are mean ± sample SD.

| Workload | Policy | IT energy (kWh) | Carbon (kg) | CPU shortfall (%) | Migrations |
| --- | --- | --- | --- | --- | --- |
'''
    for load in LOADS:
        for policy in POLICIES:
            def fmt(k,scale=1,d=3):
                v=values(policy,load,k,scale);return f'{st.mean(v):.{d}f} ± {st.stdev(v):.{d}f}'
            text+=f"| {load} | {LABELS[policy]} | {fmt('it_energy_kwh')} | {fmt('carbon_kg')} | {fmt('cpu_shortfall_fraction',100,4)} | {fmt('migrations_completed',1,1)} |\n"
    text+='\n![Policy comparison](figures/01_policy_comparison.png)\n\n## Paired comparison with THR-MMT/PABFD\n\nChanges below use workload means, not an average of per-seed percentages. Negative energy/carbon changes favor the proposed method. CPU changes are percentage points.\n\n| Load | Energy change | Carbon change | CPU shortfall change (pp) | Migrations: baseline → proposed |\n| --- | --- | --- | --- | --- |\n'
    for load in LOADS:
        def mean(p,k):return st.mean(values(p,load,k))
        e=100*(mean('proposed','it_energy_kwh')/mean('baseline','it_energy_kwh')-1)
        c=100*(mean('proposed','carbon_kg')/mean('baseline','carbon_kg')-1)
        cpu=100*(mean('proposed','cpu_shortfall_fraction')-mean('baseline','cpu_shortfall_fraction'))
        text+=f"| {load} | {e:+.2f}% | {c:+.2f}% | {cpu:+.4f} | {mean('baseline','migrations_completed'):.1f} → {mean('proposed','migrations_completed'):.1f} |\n"
    text+='''
## Measured operating trade-offs

The following operating-point figure compares the three policies on a balanced mix of low, medium and high workloads. Each policy point averages its nine full-day runs. It shows energy together with carbon and CPU delivery loss. This pooled view must be read alongside the workload-specific results above: in particular, the high-workload energy regression is not erased by an improvement in the overall average. These are measured operating points, not a Pareto front.

![Measured energy-carbon-performance trade-offs](figures/02_proposed_workload_tradeoffs.png)

| Load | Mean requested cluster CPU (%) | Mean active hosts | Applied decisions | Held decisions | Epochs containing an energy/carbon conflict |
| --- | --- | --- | --- | --- | --- |
'''
    for load in LOADS:
        fs=[r for r in front_stats if r['workload']==load]
        applied=sum(r['applied'] for r in fs);conflicts=sum(r['energy_carbon_conflict'] for r in fs)
        util=st.mean(data['proposed',load,s]['mean_requested_cluster_utilization'] for s in seeds)*100
        hosts=st.mean(values('proposed',load,'mean_active_hosts'))
        text+=f'| {load} | {util:.2f} | {hosts:.3f} | {applied}/{len(fs)} | {len(fs)-applied}/{len(fs)} | {conflicts}/{len(fs)} |\n'
    text+='''
The next figure addresses trade-offs within NSGA-II itself. It uses the high workload, the first recorded seed and the decision epoch nearest noon, selected by the same fixed rule rather than searching for an attractive outcome. All three panels project the same three-objective Pareto set. A lower-energy placement may incur more carbon or risk. Two-dimensional projections can contain apparently dominated points because the omitted objective differs. Predictions from one state are not measured full-day alternatives.

![Epoch trade-offs](figures/03_epoch_pareto_tradeoffs.png)

`pareto-alternatives.csv` records the minimum-energy, minimum-risk, minimum-carbon and recommended placement at every epoch. Those alternatives are predictions from the same state, not independently executed whole-day policies. `pareto-epochs.csv` records front sizes, unique objective triples, energy/carbon conflict and whether the recommendation was applied. Do not sum predicted epoch alternatives to claim a measured policy outcome.

The marked point is a recommendation. The controller can hold the current placement instead; decisions.csv and decision_checks.csv determine the actual action. The compromise uses weighted ideal distance (weights 1,1,1.5), with an additive distance allowance for shorter transfers. This is neither a weight-free selector nor a reproduction of a knee-point algorithm. The evolutionary search itself uses Pareto ranking, not a weighted-sum fitness.

## Consolidation behavior and performance

![Actual operation](figures/04_measured_timelines.png)

All methods use one simulated datacenter with four logical zones. PUE values are 1.10/1.20/1.60/2.00 and base CI values 90/220/440/720 g/kWh. These are illustrative experimental assumptions, not verified present-day regional grid data. CI varies by a 20% sine with zone-specific phase; renewable peaks are the configured base multiplied by 5/3.5/0.5/0.2. Zone names are scenario labels. No WAN transfer delay or network energy is modeled. Workload traces are generated, not empirical measurements.

## Interpretation and limitations

Judge energy, carbon, CPU delivery and migration overhead together; no overall winner is defined by an arbitrary combined score. In particular, a lower-energy method can deliver less requested CPU. The model accounts for both source/destination activity during native serial transfers and a 10% migrating-VM CPU loss. It does not model detailed downtime, dirty pages, application response times or deadlines. Zero SLAV is not proof of perfect performance because SLAV multiplies two factors; inspect CPU shortfall, SLATAH and PDM separately.

Environmental placement estimates include transfer activity over at most one hour; risk uses the decision interval. Demand is held at the current observation. Known synthetic environmental functions are evaluated at each predicted transfer segment start, with a piecewise constant approximation inside the segment. This is an approximation, not an accurate future trace. Optional moves require at least the manifest-recorded 0.3% predicted energy or carbon gain without worsening the other and with 10% CPU reserve. Physical overload permits risk relief, preferring candidates without predicted energy regression when available. Baseline destination admission instead uses its fixed 0.8 utilization threshold. Both share the same physical capacities.

The method is a custom implementation of established NSGA-II with problem-specific repair, serial projection, biased seeds and decision rules. It does not establish a new algorithm or global research novelty. THR-MMT/PABFD follows Beloglazov and Buyya (2012), Sections 6.2.1, 6.3 and 6.4, adapted for the common demand/RAM/eligibility model. Published absolute results from other setups are not comparable to these totals. Three repeats per workload do not support broad significance claims; all repeats and paired differences remain available.

## Reproduction and evidence

From the project root: build with scripts/mvn.ps1, run `python scripts/run-study.py results/my-study`, then `python scripts/report-study.py results/my-study`. Use a fresh output directory. No existing raw data is overwritten. Reanalysis checks each recorded run against the batch protocol and independently validates its measurements. A different current source/build is disclosed; old results remain attributed to their recorded executable. Old manifests are never rewritten.

Evidence: protocol.json, validation.json, aggregate.csv, paired-comparison.csv, pareto-alternatives.csv, pareto-epochs.csv and all raw per-run CSVs. Figures use conventional two-column layouts (7.16 inches wide), 9-point Times New Roman, linear axes, and subfigure labels. Exports are 600-dpi PNG, vector PDF with embedded fonts and editable SVG, generated by scripts/presentation_figures.py. FIGURE_GUIDE.md documents the visual encodings, sources and regeneration command. Source/version hashes, explicit units and representative-epoch selection make every displayed result traceable.

References: Deb et al. (2002), DOI 10.1109/4235.996017; Beloglazov and Buyya (2012), DOI 10.1002/cpe.1867; Calheiros et al. (2011), DOI 10.1002/spe.995.
'''
    text+=tradeoff_summary(batch)
    (batch/'study-report.md').write_text(text,encoding='utf-8')
    print(f'Validated {len(reports)} runs. Report: {batch/"study-report.md"}',flush=True)


def tradeoff_summary(batch):
    """Compare objective extremes only within the same observed state."""
    grouped=defaultdict(dict)
    for r in read_csv(batch/'pareto-alternatives.csv'):
        grouped[r['workload'],r['seed'],r['time_s']][r['alternative']]=r
    rows=[]
    for (load,seed,time),points in grouped.items():
        energy,carbon=points['min_energy'],points['min_carbon']
        risk=points['min_risk']
        e0,c0=float(energy['energy_kwh']),float(energy['carbon_kg'])
        rows.append({'workload':load,'seed':seed,'time_s':time,
            'min_carbon_vs_min_energy_energy_increase_pct':100*(float(carbon['energy_kwh'])/e0-1) if e0 else 0,
            'min_carbon_vs_min_energy_carbon_reduction_pct':100*(1-float(carbon['carbon_kg'])/c0) if c0 else 0,
            'min_carbon_vs_min_energy_risk_difference':float(carbon['performance_risk'])-float(energy['performance_risk']),
            'min_risk_vs_min_energy_energy_increase_pct':100*(float(risk['energy_kwh'])/e0-1) if e0 else 0,
            'min_risk_vs_min_energy_risk_reduction':float(energy['performance_risk'])-float(risk['performance_risk'])})
    write_csv(batch/'within_epoch_tradeoffs.csv',rows)
    text='\n## Quantifying within-state energy/carbon trade-offs\n\nFor each recorded state, compare its minimum-energy placement with its minimum-carbon placement. The table averages these paired percentage differences across all recorded states and seeds within each workload. These are equally weighted descriptive epoch comparisons, not full-day policy savings or independent experimental repeats. Alternative objective extrema may coincide.\n\n| Workload | Predicted energy increase choosing min-carbon (%) | Predicted carbon reduction choosing min-carbon (%) |\n| --- | --- | --- |\n'
    for load in LOADS:
        selected=[r for r in rows if r['workload']==load]
        text+=f"| {load} | {st.mean(r['min_carbon_vs_min_energy_energy_increase_pct'] for r in selected):.3f} | {st.mean(r['min_carbon_vs_min_energy_carbon_reduction_pct'] for r in selected):.3f} |\n"
    text+='\nChoosing the minimum-risk point instead of the minimum-energy point has the following predicted costs. Risk is a dimensionless optimization proxy; reductions below are absolute risk differences, not measured CPU-shortfall percentages.\n\n| Workload | Predicted energy increase choosing min-risk (%) | Predicted risk reduction (absolute) |\n| --- | --- | --- |\n'
    for load in LOADS:
        selected=[r for r in rows if r['workload']==load]
        text+=f"| {load} | {st.mean(r['min_risk_vs_min_energy_energy_increase_pct'] for r in selected):.3f} | {st.mean(r['min_risk_vs_min_energy_risk_reduction'] for r in selected):.4g} |\n"
    return text+'\n`within_epoch_tradeoffs.csv` also preserves the corresponding risk differences. This makes the energy/carbon price of changing the operating point explicit without pretending every alternative was executed.\n'

if __name__=='__main__':main()
