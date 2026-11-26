"""Conventional two-column research figures from the frozen verified study."""
from pathlib import Path
from collections import defaultdict
import hashlib
import json
import math
import statistics as st
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from analyze import plt,np,read_csv
from matplotlib import font_manager
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.ticker import MaxNLocator,ScalarFormatter

LOADS=('low','medium','high')
POLICIES=('static','baseline','proposed')
LABELS={'static':'Static random','baseline':'THR–MMT/PABFD','proposed':'NSGA-II'}
COLORS={'static':'#B8B8B8','baseline':'#4477AA','proposed':'#CC8844'}
HATCH={'static':'','baseline':'//','proposed':'..'}
STYLES={'static':':','baseline':'--','proposed':'-'}
IEEE_GUIDE='https://conferences.ieeeauthorcenter.ieee.org/write-your-paper/improve-your-graphics/'

def main():
    batch=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/'results/verified-study'
    protocol=json.loads((batch/'protocol.json').read_text())
    if json.loads((batch/'validation.json').read_text())['status']!='passed':
        raise ValueError('A validated study is required')
    seeds=protocol['seeds'];cfg=protocol['configuration'];out=batch/'figures';out.mkdir(exist_ok=True)
    # Use the recommended font when installed; preserve portability elsewhere.
    try:
        font_manager.findfont('Times New Roman',fallback_to_default=False)
        font='Times New Roman'
    except ValueError:
        font='DejaVu Serif'
    plt.rcParams.update({
        'font.family':font,'font.size':9,'axes.labelsize':9,'axes.titlesize':9,
        'axes.titleweight':'normal','legend.fontsize':9,'xtick.labelsize':9,'ytick.labelsize':9,
        'text.color':'black','axes.labelcolor':'black','xtick.color':'black','ytick.color':'black',
        'axes.edgecolor':'black','axes.linewidth':.6,'axes.spines.top':True,'axes.spines.right':True,
        'xtick.major.width':.6,'ytick.major.width':.6,'xtick.major.size':3,'ytick.major.size':3,
        'legend.frameon':False,'pdf.fonttype':42,'ps.fonttype':42,'svg.fonttype':'none',
        'figure.facecolor':'white','savefig.facecolor':'white','hatch.linewidth':.45,
        'mathtext.fontset':'stix','axes.formatter.use_mathtext':True,
    })
    hashes={}
    def read(path):
        hashes[path.relative_to(batch).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
        return read_csv(path)
    data={}
    for load in LOADS:
        for policy in POLICIES:
            for seed in seeds:
                data[policy,load,seed]={k:float(v) for k,v in read(batch/policy/load/str(seed)/'summary.csv')[0].items()}
    def values(policy,load,key,scale=1):return [data[policy,load,s][key]*scale for s in seeds]
    def label(ax,text):ax.set_xlabel('Workload\n'+text,labelpad=3)
    def clean(ax):
        ax.set_axisbelow(True)
        ax.grid(axis='y',color='#DDDDDD',linewidth=.4)
        ax.tick_params(direction='out')
    exports=[]
    def save(fig,name):
        # Fixed physical dimensions preserve the intended final-size typography.
        fig.savefig(out/(name+'.png'),dpi=600)
        fig.savefig(out/(name+'.pdf'))
        fig.savefig(out/(name+'.svg'))
        exports.append({'name':name,'width_in':float(fig.get_figwidth()),'height_in':float(fig.get_figheight())})
        plt.close(fig)
        print('Rendered',name,flush=True)
    legend=[Patch(facecolor=COLORS[p],edgecolor='black',linewidth=.5,hatch=HATCH[p],label=LABELS[p]) for p in POLICIES]

    # 1. Familiar grouped bars; common linear axes retain physical zero.
    fig,axes=plt.subplots(2,2,figsize=(7.16,4.7))
    fig.subplots_adjust(left=.10,right=.98,bottom=.12,top=.90,wspace=.32,hspace=.62)
    metrics=[('it_energy_kwh','IT energy (kWh)',1,'(a) Energy consumption'),
             ('carbon_kg','Carbon emissions (kg)',1,'(b) Carbon emissions'),
             ('cpu_shortfall_fraction','CPU shortfall (%)',100,'(c) CPU delivery loss'),
             ('migrations_completed','Completed migrations',1,'(d) Migration overhead')]
    x=np.arange(len(LOADS));width=.24
    for ax,(key,ylabel,scale,caption) in zip(axes.flat,metrics):
        for j,policy in enumerate(POLICIES):
            samples=[values(policy,load,key,scale) for load in LOADS]
            means=np.array([st.mean(v) for v in samples]);sd=np.array([st.stdev(v) if len(v)>1 else 0 for v in samples])
            ax.bar(x+(j-1)*width,means,width=width,color=COLORS[policy],edgecolor='black',linewidth=.5,
                   hatch=HATCH[policy],yerr=np.vstack((np.minimum(means,sd),sd)),capsize=2,
                   error_kw={'elinewidth':.65,'capthick':.65})
        ax.set(xticks=x,xticklabels=[l.title() for l in LOADS],ylabel=ylabel,ylim=(0,None))
        ax.yaxis.set_major_locator(MaxNLocator(4));ax.margins(y=.14);label(ax,caption);clean(ax)
    fig.legend(handles=legend,loc='upper center',bbox_to_anchor=(.54,.995),ncol=3,columnspacing=1.5,handlelength=2)
    save(fig,'01_policy_comparison')

    # 2. Measured operating trade-offs, analogous to the reference report's cost-latency view.
    fig,axes=plt.subplots(1,2,figsize=(7.16,2.65))
    fig.subplots_adjust(left=.10,right=.98,bottom=.24,top=.94,wspace=.38)
    marker={'static':'s','baseline':'^','proposed':'o'}
    for col,key in enumerate(('carbon_kg','cpu_shortfall_fraction')):
        ax=axes[col]
        for policy in POLICIES:
            # Balanced nine-run workload mix: three loads, three paired seeds each.
            xx=st.mean(data[policy,l,s]['it_energy_kwh'] for l in LOADS for s in seeds)
            yy=st.mean(data[policy,l,s][key] for l in LOADS for s in seeds)*(100 if col else 1)
            ax.scatter(xx,yy,s=42,marker=marker[policy],color=COLORS[policy],edgecolors='black',linewidth=.5,zorder=3)
            offset={'static':(-5,-18),'baseline':(4,5),'proposed':(5,5)}[policy]
            ax.annotate(LABELS[policy],(xx,yy),xytext=offset,textcoords='offset points',fontsize=9,
                        ha='right' if policy=='static' else 'left')
        ax.set_xlabel('Mean IT energy (kWh)\n'+('(a) Energy–carbon trade-off' if col==0 else '(b) Energy–performance trade-off'))
        ax.set_ylabel('Mean carbon emissions (kg)' if col==0 else 'Mean CPU shortfall (%)')
        ax.set_xlim(25,35);ax.set_ylim((15,26) if col==0 else (0,1))
        ax.xaxis.set_major_locator(MaxNLocator(4));ax.yaxis.set_major_locator(MaxNLocator(4));clean(ax)
        ax.text(.96,.06,'Lower-left is preferable',transform=ax.transAxes,ha='right',fontsize=9,fontstyle='italic')
    save(fig,'02_proposed_workload_tradeoffs')

    # 3. One fixed NSGA-II state: show all three pairwise objective trade-offs.
    load='high';raw=read(batch/'proposed'/load/str(seeds[0])/'pareto_fronts.csv')
    times=sorted(set(float(r['time_s']) for r in raw));t=min(times,key=lambda t:(abs(t-43200),t))
    front=[r for r in raw if float(r['time_s'])==t]
    points=np.array([[float(r[k]) for k in ('energy_kwh','performance_risk','carbon_kg')] for r in front])
    chosen=next(i for i,r in enumerate(front) if r['selected']=='true')
    epoch_info=[{'workload':load,'seed':seeds[0],'time_s':t,'placements':len(front)}]
    fig,axes=plt.subplots(1,3,figsize=(7.16,2.7))
    fig.subplots_adjust(left=.09,right=.98,bottom=.24,top=.81,wspace=.60)
    for ax,(a,b,xlabel,ylabel,caption) in zip(axes,[
        (0,2,'Predicted IT energy (kWh)','Predicted carbon (kg)','(a) Energy and carbon'),
        (0,1,'Predicted IT energy (kWh)','Performance risk','(b) Energy and performance'),
        (1,2,'Performance risk','Predicted carbon (kg)','(c) Performance and carbon')]):
        ax.scatter(points[:,a],points[:,b],s=12,color='#4477AA',alpha=.65,edgecolors='none')
        ax.scatter(points[chosen,a],points[chosen,b],s=60,marker='*',color='#CC6633',edgecolor='black',linewidth=.45,zorder=4)
        ax.set(xlabel=xlabel+'\n'+caption,ylabel=ylabel)
        ax.xaxis.set_major_locator(MaxNLocator(3));ax.yaxis.set_major_locator(MaxNLocator(3))
        for axis in (ax.xaxis,ax.yaxis):
            formatter=ScalarFormatter(useMathText=True,useOffset=False);formatter.set_powerlimits((-3,4));axis.set_major_formatter(formatter)
        ax.margins(x=.12,y=.15)
    fig.legend(handles=[Line2D([],[],ls='none',marker='o',markersize=4,color='#4477AA',label='Pareto solutions'),
                        Line2D([],[],ls='none',marker='*',markersize=8,color='#CC6633',markeredgecolor='black',markeredgewidth=.45,label='Recommended solution')],
               loc='upper center',bbox_to_anchor=(.54,.995),ncol=2)
    save(fig,'03_epoch_pareto_tradeoffs')

    # 4. Link capacity decisions to observed performance for the common high-demand trace.
    fig,axes=plt.subplots(2,1,figsize=(7.16,3.8))
    fig.subplots_adjust(left=.11,right=.98,bottom=.15,top=.87,hspace=.60)
    bin_seconds=300.;duration=float(cfg['duration']);n=int(np.ceil(duration/bin_seconds))
    for policy in POLICIES:
        totals=np.zeros((n,3))
        demand=defaultdict(dict)
        for r in read(batch/policy/'high'/str(seeds[0])/'workload.csv'):
            demand[float(r['time_s'])][int(r['vm'])]=float(r['requested_mips'])
        moves=read(batch/policy/'high'/str(seeds[0])/'migrations.csv')
        intervals=defaultdict(dict)
        for r in read(batch/policy/'high'/str(seeds[0])/'host_metrics.csv'):
            intervals[float(r['start_s']),float(r['end_s'])][int(r['host'])]=r
        move_index=0
        for (start,end),rows in sorted(intervals.items()):
            end=min(end,duration)
            if end<=start:continue
            sample=math.floor((start+1e-7)/int(cfg['sample']))*int(cfg['sample'])
            requested=sum(demand[sample].values())
            delivered=sum(float(r['delivered_mips']) for r in rows.values())
            midpoint=(start+end)/2
            while move_index<len(moves) and float(moves[move_index]['finish_s'])<=midpoint:move_index+=1
            if move_index<len(moves) and float(moves[move_index]['start_s'])<=midpoint<float(moves[move_index]['finish_s']):
                move=moves[move_index];vm_demand=demand[sample][int(move['vm'])]
                for host in (int(move['source']),int(move['destination'])):
                    capacity=3720 if host<int(cfg['hosts'])//2 else 5320
                    delivered-=.1*vm_demand*min(1,capacity/max(1e-12,float(rows[host]['requested_mips'])))
            active=sum(r['active']=='true' for r in rows.values())
            first=int(start//bin_seconds);last=min(n-1,int(np.ceil(end/bin_seconds))-1)
            for index in range(first,last+1):
                overlap=max(0,min(end,(index+1)*bin_seconds)-max(start,index*bin_seconds))
                totals[index]+=overlap*np.array([active,requested,delivered])
        widths=np.minimum(bin_seconds,duration-np.arange(n)*bin_seconds)
        summary=data[policy,'high',seeds[0]]
        assert np.isclose(totals[:,0].sum()/duration,summary['mean_active_hosts'],rtol=1e-8,atol=1e-10)
        assert np.isclose(1-totals[:,2].sum()/totals[:,1].sum(),summary['cpu_shortfall_fraction'],rtol=1e-8,atol=1e-10)
        hosts=totals[:,0]/widths
        loss=np.maximum(0,100*np.divide(totals[:,1]-totals[:,2],totals[:,1],out=np.zeros(n),where=totals[:,1]>0))
        hours=np.arange(n+1)*bin_seconds/3600;hours[-1]=duration/3600
        for ax,series in zip(axes,(hosts,loss)):
            ax.step(hours,np.r_[series,series[-1]],where='post',color=COLORS[policy],ls=STYLES[policy],lw=1)
    for ax,ylabel,caption in zip(axes,['Mean active hosts','CPU shortfall (%)'],
                                 ['(a) Consolidation behavior','(b) Observed CPU delivery loss']):
        ax.set(xlim=(0,24),xticks=[0,6,12,18,24],ylabel=ylabel,xlabel='Time (h)\n'+caption)
        ax.yaxis.set_major_locator(MaxNLocator(4));clean(ax)
    axes[1].set_ylim(bottom=0)
    fig.legend(handles=[Line2D([],[],color=COLORS[p],ls=STYLES[p],lw=1.2,label=LABELS[p]) for p in POLICIES],
               loc='upper center',bbox_to_anchor=(.54,.995),ncol=3,columnspacing=1.5)
    save(fig,'04_measured_timelines')
    (out/'visualization-provenance.json').write_text(json.dumps({
        'study':str(batch),'seeds':seeds,'input_sha256':hashes,
        'temporal_bin_seconds':300,'balanced_policy_mix':'equal weight to each of three workloads and three seeds', 'model_changed':False,'simulation_rerun':False,'font':font,'font_size_pt':9,
        'exports':exports,'formats':['PNG 600 dpi','PDF embedded TrueType fonts','SVG editable text'],
        'reference_guidance':[IEEE_GUIDE], 'content_reference':r'C:\Users\ASUS\Downloads\SLA_AutoScaling_IEEE_Report_final.pdf', 'representative_pareto_epochs':epoch_info,
        'notes':['Grouped bars: means and sample SD; lower whiskers bounded at zero.',
                 'All comparison axes linear; small low-load losses remain in report tables.',
                 'Within-state trade-off means weight recorded states equally; no independence claim.',
                 'Pareto figures are discrete two-dimensional projections, without interpolation.']},indent=2)+'\n',encoding='utf-8')
    print('Finished four objective-focused figures. Simulation and raw results unchanged.')

if __name__=='__main__':main()
