"""Run a frozen, three-policy matched experiment. Python standard library only."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
POLICIES={'static':'static','baseline':'thr_mmt_pabfd','proposed':'nsga2'}

def main():
    output=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/'results/verified-study'
    seeds=[int(x) for x in sys.argv[2].split(',')] if len(sys.argv)>2 else [1011,2022,3033]
    if len(seeds)<2 or len(set(seeds))!=len(seeds):raise ValueError('Use at least two distinct seeds')
    if output.exists():raise FileExistsError('Choose a new output directory: '+str(output))
    cfg={}
    for line in (ROOT/'config/default.properties').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key,value=line.split('=',1);cfg[key.strip()]=value.strip()
    jar=ROOT/'target/vm-consolidation-1.0.0.jar'
    jar_hash=hashlib.sha256(jar.read_bytes()).hexdigest()
    digest=hashlib.sha256()
    for p in sorted((ROOT/'src').rglob('*')):
        if p.is_file():digest.update(p.relative_to(ROOT).as_posix().encode());digest.update(p.read_bytes())
    digest.update((ROOT/'pom.xml').read_bytes())
    protocol={'seeds':seeds,'workloads':['low','medium','high'],'policies':POLICIES,'configuration':cfg,
              'source_sha256':digest.hexdigest(),'jar_sha256':jar_hash,'initialization':'shared seeded random repair',
              'minimum_environmental_gain':.003,'selection_weights':[1,1,1.5],
              'pue':[1.10,1.20,1.60,2.00],'base_ci_g_kwh':[90,220,440,720],
              'renewable_multipliers':[5,3.5,.5,.2], 'environment':'synthetic_four_zone',
              'note':'No tuning on this batch; seeds chosen before running. No significance claim from three repeats.'}
    output.mkdir(parents=True)
    (output/'protocol.json').write_text(json.dumps(protocol,indent=2)+'\n')
    java=ROOT/'.tools/jdk/bin/java.exe'
    for load in protocol['workloads']:
        for seed in seeds:
            for name,policy in POLICIES.items():
                if hashlib.sha256(jar.read_bytes()).hexdigest()!=jar_hash:raise RuntimeError('JAR changed during study')
                run=output/name/load/str(seed);run.mkdir(parents=True)
                args=[str(java) if java.exists() else 'java','-jar',str(jar)]
                args += [f'{k}={v}' for k,v in cfg.items() if k not in ('workload','seed')]
                args += [f'workload={load}',f'seed={seed}',f'policy={policy}',f'output={run}']
                with (run/'console.log').open('w',encoding='utf-8') as log:
                    subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
                print(f'Completed {name}/{load}/{seed}',flush=True)
    print(f'All {len(POLICIES)*len(protocol["workloads"])*len(seeds)} runs completed. Analyze with: python scripts/report-study.py '+str(output))

if __name__=='__main__':main()
