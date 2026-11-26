"""Validate raw outputs, summarize repeats, and draw figures. No fabricated values."""
from pathlib import Path
import ast
import csv
import hashlib
import json
import math
import os
import re
import sys
from collections import defaultdict

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault('MPLCONFIGDIR', str(ROOT / '.tools' / 'matplotlib-cache'))
sys.path.insert(0, str(ROOT / '.tools' / 'python-packages'))
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.ticker import StrMethodFormatter, MaxNLocator


def read_csv(path):
    with path.open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream))


def close(a, b, label):
    if not math.isclose(a, b, rel_tol=1e-8, abs_tol=1e-9):
        raise ValueError(f'{label}: {a} != {b}')


def validate(run):
    manifest = (run / 'manifest.properties').read_text()
    if not manifest.startswith('status=complete\n'):
        raise ValueError(f'Incomplete run {run}')
    cfg = dict(re.findall(r'(\w+)=([^,\]\n]+)', manifest.split('config=Config[', 1)[1].split(']\n')[0]))
    n, m, duration = int(cfg['vms']), int(cfg['hosts']), int(cfg['duration'])
    summary = {k: float(v) for k, v in read_csv(run / 'summary.csv')[0].items()}
    if not all(math.isfinite(v) for v in summary.values()):
        raise ValueError('Non-finite summary')
    close(summary['duration_s'], duration, 'horizon')
    hosts, zones, moves = (read_csv(run / f) for f in ('host_metrics.csv', 'zone_metrics.csv', 'migrations.csv'))
    close(sum(float(r['energy_kwh']) for r in hosts), summary['it_energy_kwh'], 'IT energy')
    for column, key in [('facility_kwh', 'facility_energy_kwh'), ('grid_kwh', 'grid_energy_kwh'), ('carbon_kg', 'carbon_kg')]:
        close(sum(float(r[column]) for r in zones), summary[key], key)
    zone_id={'Oregon':0,'California':1,'Virginia':2,'Dallas':3}
    for row in zones:
        z=zone_id[row['zone']];start=float(row['start_s']);dt=float(row['end_s'])-start
        observed=math.floor((start+1e-7)/int(cfg['sample']))*int(cfg['sample'])
        ci=[90,220,440,720][z]*(1+.20*math.sin(2*math.pi*observed/86400+z*.7))
        renewable=float(cfg['renewablePeakKw'])*[5,3.5,.5,.2][z]*max(0,math.sin(math.pi*((observed/3600)%24-6)/12))*dt/3600
        close(float(row['facility_kwh']),float(row['it_kwh'])*[1.10,1.20,1.60,2.00][z],'zone PUE')
        close(float(row['grid_kwh']),max(0,float(row['facility_kwh'])-renewable),'zone renewable offset')
        close(float(row['ci_g_per_kwh']),ci,'zone CI')
        close(float(row['carbon_kg']),float(row['grid_kwh'])*ci/1000,'zone carbon')
    active = defaultdict(float)
    saturated = defaultdict(float)
    for row in hosts:
        j = int(row['host'])
        dt = float(row['end_s'])-float(row['start_s'])
        if dt <= 0:
            raise ValueError('Nonpositive accounting interval')
        close(float(row['energy_kwh']), float(row['power_w'])*dt/3600000, 'power integration')
        if row['active'] == 'true':
            active[j] += dt
            capacity = 3720 if j < m//2 else 5320
            if float(row['requested_mips']) >= capacity-1e-8:
                saturated[j] += dt
    close(sum(active.values())/duration, summary['mean_active_hosts'], 'active hosts')
    close(sum(saturated[j]/active[j] for j in active)/m, summary['slatah'], 'SLATAH')
    close(summary['slatah']*summary['pdm_model'], summary['slav'], 'SLAV')
    close(summary['it_energy_kwh']*summary['slav'], summary['esv'], 'ESV')
    close(len(moves), summary['migrations_completed'], 'migration count')
    close(summary['migrations_started'], summary['migrations_completed'], 'unfinished migrations')
    for row in moves:
        vm = int(row['vm'])
        expected = [870, 1740, 1740, 613][vm % 4]/62.5
        close(float(row['duration_s']), expected, 'native migration duration')
        if float(row['finish_s']) > duration+1e-7:
            raise ValueError('Migration passed measurement horizon')
    demand_by_time = defaultdict(dict)
    for row in read_csv(run / 'workload.csv'):
        demand_by_time[float(row['time_s'])][int(row['vm'])] = float(row['requested_mips'])
    close(sum(sum(d.values()) for d in demand_by_time.values())*int(cfg['sample']), summary['requested_mi'], 'requested CPU-time')
    # Independently remove migration CPU work from host totals to recover delivered
    # application CPU-time. Serial migration makes the attribution unambiguous.
    intervals = defaultdict(dict)
    for row in hosts:
        intervals[(float(row['start_s']), float(row['end_s']))][int(row['host'])] = row
    move_index, application_mi = 0, 0.
    lost_by_vm = np.zeros(n)
    for (start, end), rows in intervals.items():
        dt, midpoint = end-start, (start+end)/2
        delivered = sum(float(r['delivered_mips']) for r in rows.values())
        while move_index < len(moves) and float(moves[move_index]['finish_s']) <= midpoint:
            move_index += 1
        if move_index < len(moves) and float(moves[move_index]['start_s']) <= midpoint < float(moves[move_index]['finish_s']):
            move = moves[move_index]
            vm = int(move['vm'])
            sample_time = math.floor((start+1e-7)/int(cfg['sample']))*int(cfg['sample'])
            d = demand_by_time[sample_time][vm]
            lost_by_vm[vm] += .1*d*dt
            for host_id in (int(move['source']), int(move['destination'])):
                capacity = 3720 if host_id < m//2 else 5320
                load = float(rows[host_id]['requested_mips'])
                delivered -= .1*d*min(1, capacity/max(1e-12, load))
        application_mi += delivered*dt
    close(application_mi, summary['delivered_mi'], 'delivered application CPU-time')
    close(max(0, 1-application_mi/summary['requested_mi']), summary['cpu_shortfall_fraction'], 'CPU shortfall')
    requested_by_vm = np.array([sum(d[i] for d in demand_by_time.values())*int(cfg['sample']) for i in range(n)])
    close(np.mean(lost_by_vm/requested_by_vm), summary['pdm_model'], 'PDM model')
    fronts = defaultdict(list)
    for row in read_csv(run / 'pareto_fronts.csv'):
        fronts[float(row['time_s'])].append(row)
    snapshots = defaultdict(dict)
    if (run / 'snapshots.csv').exists():
        for row in read_csv(run / 'snapshots.csv'):
            snapshots[float(row['time_s'])][int(row['vm'])] = row
        if not snapshots:raise ValueError('No initial snapshot')
        first_snapshot=snapshots[min(snapshots)]
        replay_hosts={i:int(row['host']) for i,row in first_snapshot.items()}
        replay_finish={i:float('-inf') for i in first_snapshot}
        replay_index=0
        for t, snapshot in sorted(snapshots.items()):
            while replay_index<len(moves) and float(moves[replay_index]['finish_s'])<=t+1e-8:
                move=moves[replay_index];i=int(move['vm'])
                if replay_hosts[i]!=int(move['source']):raise ValueError('Migration source disagrees with replay')
                replay_hosts[i]=int(move['destination']);replay_finish[i]=float(move['finish_s']);replay_index+=1
            if len(snapshot) != n: raise ValueError('Incomplete recorded snapshot')
            cpu = np.zeros(m)
            for i, row in snapshot.items():
                if int(row['host'])!=replay_hosts[i]:raise ValueError('Snapshot placement disagrees with completed migrations')
                close(float(row['last_migration_s']),replay_finish[i],'last completed migration')
                close(float(row['requested_mips']), demand_by_time[t][i], 'snapshot demand')
                cpu[int(row['host'])] += float(row['requested_mips'])
            global_escape = 'cooldown_policy=global_overload_escape_v1' in manifest and np.any(cpu > np.array([3720]*(m//2)+[5320]*(m//2))+1e-8)
            global_escape |= 'cooldown_policy=cluster_headroom_escape_v2' in manifest and np.any(cpu > float(cfg['headroom'])*np.array([3720]*(m//2)+[5320]*(m//2))+1e-8)
            for i, row in snapshot.items():
                host = int(row['host']); capacity = 3720 if host < m//2 else 5320
                eligible = global_escape or t-float(row['last_migration_s']) >= int(cfg.get('cooldown','0')) or cpu[host] > float(cfg['headroom'])*capacity+1e-8
                if eligible != (row['can_migrate']=='true'): raise ValueError('Invalid cooldown mask')
    for t, front in fronts.items():
        if sum(r['selected'] == 'true' for r in front) != 1:
            raise ValueError('Expected exactly one selected compromise per front')
        points = np.array([[float(r[k]) for k in ('energy_kwh', 'performance_risk', 'carbon_kg')] for r in front])
        if not np.isfinite(points).all():
            raise ValueError('Non-finite Pareto point')
        for point in points:
            if np.any(np.all(points <= point, axis=1) & np.any(points < point, axis=1)):
                raise ValueError(f'Dominated point at {run} time {t}')
        if float(cfg.get('selectionSlack','0')) > 0 and 'selector=benefit_guard_' not in manifest:
            if t not in snapshots: raise ValueError('Selector verification requires recorded placement')
            lo,hi=np.min(points,axis=0),np.max(points,axis=0)
            scale=np.where(hi>lo,hi-lo,1)
            distances=np.sqrt(np.sum(((points-lo)/scale)**2,axis=1))
            eligible=distances<=np.min(distances)+float(cfg['selectionSlack'])+1e-12
            costs=np.array([sum([870,1740,1740,613][i%4]/62.5
                               for i,h in enumerate(ast.literal_eval(r['placement']))
                               if h!=int(snapshots[t][i]['host'])) for r in front])
            selected=next(i for i,r in enumerate(front) if r['selected']=='true')
            if not eligible[selected]: raise ValueError('Selected point outside ideal-distance allowance')
            close(costs[selected],np.min(costs[eligible]),'least transfer time inside distance band')
        for row in front:
            p = ast.literal_eval(row['placement'])
            if len(p) != n or any(h < 0 or h >= m for h in p):
                raise ValueError('Invalid assignment')
            cpu, ram, count = np.zeros(m), np.zeros(m), np.zeros(m)
            for i, h in enumerate(p):
                if snapshots and snapshots[t][i]['can_migrate']=='false' and h!=int(snapshots[t][i]['host']):
                    raise ValueError('Pareto placement violated cooldown')
                if [2500, 2000, 1000, 500][i % 4] > (1860 if h < m//2 else 2660):
                    raise ValueError('Ineligible virtual core')
                cpu[h] += demand_by_time[t][i]
                ram[h] += [870, 1740, 1740, 613][i % 4]
                count[h] += 1
            if np.any(cpu > np.array([3720]*(m//2)+[5320]*(m//2))+1e-7) or np.any(ram > 4096) or np.any(count*10 > 1000):
                raise ValueError('Infeasible Pareto placement')
    if 'selector=benefit_guard_v4' in manifest or 'selector=benefit_guard_v5' in manifest:
        match=re.search(r'^minimum_environmental_gain=([^\n]+)',manifest,re.M)
        gain=float(match.group(1)) if match else .003
        if not (0<gain<1):raise ValueError('Invalid recorded acceptance threshold')
        checks=read_csv(run/'decision_checks.csv')
        if len(checks)!=len(fronts):raise ValueError('Missing decision audit rows')
        decisions={float(r['time_s']):r for r in read_csv(run/'decisions.csv') if r['status'] in ('hold_current','selected_target')}
        for row in checks:
            t=float(row['time_s']);apply=row['apply']=='true';reason=row['reason']
            selected=next(r for r in fronts[t] if r['selected']=='true')
            for key,objective in [('energy_kwh','energy_kwh'),('risk','performance_risk'),('carbon_kg','carbon_kg')]:
                close(float(row['candidate_'+key]),float(selected[objective]),'decision candidate')
            capacities=np.array([3720]*(m//2)+[5320]*(m//2))
            actual_cpu=np.zeros(m);target_cpu=np.zeros(m)
            selected_placement=ast.literal_eval(selected['placement'])
            for i in range(n):
                actual_cpu[int(snapshots[t][i]['host'])]+=demand_by_time[t][i]
                target_cpu[selected_placement[i]]+=demand_by_time[t][i]
            if apply and reason=='risk_relief' and not np.any(actual_cpu>capacities+1e-8):raise ValueError('Risk escape without physical overload')
            if apply and reason=='net_benefit' and np.any(target_cpu>.9*capacities+1e-8):raise ValueError('Optional transfer violates CPU reserve')
            if apply and ast.literal_eval(decisions[t]['placement'])!=selected_placement:raise ValueError('Applied target differs from recommendation')
            if apply and reason=='risk_relief':
                if not float(row['candidate_risk'])<float(row['stay_risk'])-1e-12:raise ValueError('Unjustified risk transfer')
            elif apply and reason=='net_benefit':
                e,c,se,sc=[float(row[k]) for k in ('candidate_energy_kwh','candidate_carbon_kg','stay_energy_kwh','stay_carbon_kg')]
                if e>se+1e-12 or c>sc+1e-12 or not((se>1e-12 and e<=(1-gain)*se) or (sc>1e-12 and c<=(1-gain)*sc)):raise ValueError('Unjustified environmental transfer')
            elif apply:raise ValueError('Unknown transfer justification')
            if 'selector=benefit_guard_v5' in manifest:
                observed_risk=float(np.mean((np.maximum(0,actual_cpu/capacities-float(cfg['headroom']))/(1-float(cfg['headroom'])))**2))
                close(float(row['stay_risk']),observed_risk,'stay headroom risk')
                overloaded=bool(np.any(actual_cpu>capacities+1e-8))
                pool=[]
                current=[int(snapshots[t][i]['host']) for i in range(n)]
                se,sc=float(row['stay_energy_kwh']),float(row['stay_carbon_kg'])
                for candidate in fronts[t]:
                    placement=ast.literal_eval(candidate['placement'])
                    if placement==current:continue
                    e,risk,c=[float(candidate[k]) for k in ('energy_kwh','performance_risk','carbon_kg')]
                    if overloaded:
                        ok=risk<float(row['stay_risk'])-1e-12
                    else:
                        cpu=np.zeros(m)
                        for i,h in enumerate(placement):cpu[h]+=demand_by_time[t][i]
                        ok=(np.all(cpu<=.9*capacities+1e-8) and e<=se+1e-12 and c<=sc+1e-12
                            and ((se>1e-12 and e<=(1-gain)*se) or (sc>1e-12 and c<=(1-gain)*sc)))
                    if ok:pool.append(candidate)
                if overloaded:
                    safe=[c for c in pool if float(c['energy_kwh'])<=se+1e-12]
                    if safe:pool=safe
                if apply!=bool(pool):raise ValueError('Acceptance differs from full candidate eligibility')
                pool=pool or fronts[t]
                points=np.array([[float(c[k]) for k in ('energy_kwh','performance_risk','carbon_kg')] for c in pool])
                lo,hi=points.min(axis=0),points.max(axis=0)
                distances=np.sqrt(np.sum(((points-lo)/np.where(hi>lo,hi-lo,1))**2*np.array([1,1,1.5]),axis=1))
                inside=distances<=distances.min()+float(cfg['selectionSlack'])+1e-12
                if selected not in pool:raise ValueError('Recommendation outside decision pool')
                index=pool.index(selected)
                if not inside[index]:raise ValueError('Recommendation outside weighted distance allowance')
                costs=[sum([870,1740,1740,613][i%4]/62.5 for i,h in enumerate(ast.literal_eval(c['placement'])) if h!=current[i]) for c in pool]
                if float(cfg['selectionSlack'])>0:
                    close(costs[index],min(v for v,yes in zip(costs,inside) if yes),'minimum transfer cost inside weighted band')
            if (decisions[t]['status']=='selected_target')!=apply:raise ValueError('Audit/action mismatch')
            if not apply:
                actual=ast.literal_eval(decisions[t]['placement'])
                if actual!=[int(snapshots[t][i]['host']) for i in range(n)]:raise ValueError('Held placement changed')
    expected_hash = re.search(r'workload_sha256=(\w+)', manifest).group(1)
    if hashlib.sha256((run / 'workload.csv').read_bytes()).hexdigest() != expected_hash:
        raise ValueError('Workload checksum mismatch')
    return summary, fronts, hosts, {'run': str(run.relative_to(ROOT)) if run.is_relative_to(ROOT) else str(run), 'fronts': len(fronts),
            'pareto_points': sum(map(len, fronts.values())), 'validation': 'passed'}


def save(fig, folder, name):
    # Matplotlib's 3D tight bounds can crop the last z-axis label.
    bounds = None if name == 'pareto_3d' else 'tight'
    fig.savefig(folder / f'{name}.png', dpi=220, bbox_inches=bounds)
    fig.savefig(folder / f'{name}.svg', bbox_inches=bounds)
    plt.close(fig)


def main():
    experiment = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / 'results' / 'verified-study' / 'proposed'
    figure_dir = experiment / 'figures'
    figure_dir.mkdir(exist_ok=True)
    plt.rcParams.update({'font.size': 10, 'axes.spines.top': False, 'axes.spines.right': False})
    data, reports = defaultdict(list), []
    for run in sorted(experiment.glob('*/*')):
        if not (run / 'manifest.properties').exists():
            continue
        summary, fronts, hosts, report = validate(run)
        # Only the first seed's detailed data is used for illustrative plots.
        # Keep summaries for all seeds without retaining every raw row in memory.
        if data[run.parent.name]: fronts, hosts = {}, []
        data[run.parent.name].append((summary, fronts, hosts, run))
        reports.append(report)
    if not data:
        raise ValueError('No completed experiments found')
    (experiment / 'validation.json').write_text(json.dumps(reports, indent=2))
    metrics = ['it_energy_kwh', 'carbon_kg', 'slatah', 'pdm_model', 'slav', 'cpu_shortfall_fraction', 'mean_active_hosts', 'migrations_completed']
    loads = [load for load in ('low', 'medium', 'high') if load in data]
    with (experiment / 'aggregate.csv').open('w', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['workload', 'runs']+[f'{key}_{stat}' for key in metrics for stat in ('mean', 'sample_std')])
        for load in loads:
            values = [[np.mean([r[0][key] for r in data[load]]), np.std([r[0][key] for r in data[load]], ddof=1) if len(data[load])>1 else float('nan')] for key in metrics]
            writer.writerow([load, len(data[load])]+[value for pair in values for value in pair])
    fig, axes = plt.subplots(1, 3, figsize=(12, 3.7), layout='constrained')
    for ax, key, label in zip(axes, ['it_energy_kwh', 'carbon_kg', 'cpu_shortfall_fraction'], ['IT energy (kWh)', 'Operational carbon (kg)', 'CPU shortfall (fraction)']):
        means = [np.mean([r[0][key] for r in data[load]]) for load in loads]
        stds = [np.std([r[0][key] for r in data[load]], ddof=1) if len(data[load])>1 else 0 for load in loads]
        ax.bar(loads, means, yerr=stds, capsize=5, color=['#26828e', '#4774a5', '#b46d46'][:len(loads)])
        ax.set_ylabel(label); ax.grid(axis='y', alpha=.18)
    fig.suptitle('Measured simulation outcomes | error bars: sample standard deviation')
    save(fig, figure_dir, 'workload_summary')
    chosen = []
    for load in loads:
        _, fronts, _, run = data[load][0]
        # A transparent display rule: epoch with most unique objective triples, earliest tie.
        t = max(fronts, key=lambda t: len(set(tuple(front[k] for k in ('energy_kwh','performance_risk','carbon_kg')) for front in fronts[t])))
        chosen.append((load, t, fronts[t], run))
    fig = plt.figure(figsize=(5.5*len(chosen), 5.0))
    fig.subplots_adjust(left=.02, right=.94, bottom=.12, top=.82, wspace=.25)
    for index, (load, t, front, run) in enumerate(chosen):
        ax = fig.add_subplot(1, len(chosen), index+1, projection='3d')
        points = np.array([[float(r[k]) for k in ('energy_kwh', 'performance_risk', 'carbon_kg')] for r in front])
        selected = np.array([r['selected']=='true' for r in front])
        display = points*np.array([1000, 1, 1000])  # Wh and grams avoid crowded exponent offsets.
        ax.scatter(*display.T, s=25, color='#26828e', alpha=.65)
        ax.scatter(*display[selected].T, marker='*', s=160, color='#c43d3d', label='Selected compromise')
        ax.set_xlabel('IT energy (Wh)',fontsize=9);ax.set_ylabel('Performance risk',fontsize=9);ax.set_zlabel('Carbon (g)',fontsize=9)
        for axis in (ax.xaxis, ax.yaxis, ax.zaxis): axis.set_major_formatter(StrMethodFormatter('{x:.3g}'))
        ax.tick_params(labelsize=8)
        ax.set_title(f'{load.title()} | seed {run.name} | t={t/3600:.2f} h',fontsize=10);ax.legend(fontsize=8)
    fig.suptitle('Separate epoch Pareto sets | estimates over one decision horizon')
    save(fig, figure_dir, 'pareto_3d')
    fig, axes = plt.subplots(len(chosen), 3, figsize=(12, 3.2*len(chosen)), squeeze=False, layout='constrained')
    pairs = [('energy_kwh','performance_risk'),('energy_kwh','carbon_kg'),('performance_risk','carbon_kg')]
    labels = {'energy_kwh':'Estimated IT energy (Wh)', 'performance_risk':'Performance risk', 'carbon_kg':'Estimated carbon (g)'}
    scale = {'energy_kwh':1000, 'performance_risk':1, 'carbon_kg':1000}
    for row, (load, t, front, _) in enumerate(chosen):
        for ax, (x, y) in zip(axes[row], pairs):
            ax.scatter([float(p[x])*scale[x] for p in front],[float(p[y])*scale[y] for p in front],s=25,alpha=.6)
            selected = next(p for p in front if p['selected']=='true')
            ax.scatter(float(selected[x])*scale[x],float(selected[y])*scale[y],marker='*',s=150,color='#c43d3d')
            ax.set(xlabel=labels[x],ylabel=labels[y],title=f'{load.title()} | t={t/3600:.2f} h');ax.grid(alpha=.2)
            ax.xaxis.set_major_locator(MaxNLocator(nbins=4));ax.yaxis.set_major_locator(MaxNLocator(nbins=5))
            ax.xaxis.set_major_formatter(StrMethodFormatter('{x:.4g}'));ax.yaxis.set_major_formatter(StrMethodFormatter('{x:.4g}'))
    save(fig, figure_dir, 'pareto_projections')
    fig, axes = plt.subplots(2, 1, figsize=(10, 6), sharex=True, layout='constrained')
    for load in loads:
        by_time = defaultdict(lambda: [0.,0.])
        for row in data[load][0][2]:
            t = float(row['start_s'])
            by_time[t][0] += float(row['power_w'])
            by_time[t][1] += row['active']=='true'
        times = sorted(by_time)
        axes[0].step(np.array(times)/3600,[by_time[t][0] for t in times],where='post',label=load)
        axes[1].step(np.array(times)/3600,[by_time[t][1] for t in times],where='post',label=load)
    axes[0].set_ylabel('Total IT power (W)');axes[1].set_ylabel('Active hosts');axes[1].set_xlabel('Simulated time (h)')
    for ax in axes: ax.legend();ax.grid(alpha=.2)
    fig.suptitle('First recorded seed per workload | measured operation')
    save(fig, figure_dir, 'timelines')
    print(f'Validated {len(reports)} runs. Summary: {experiment / "aggregate.csv"}. Figures: {figure_dir}')


if __name__ == '__main__':
    main()

