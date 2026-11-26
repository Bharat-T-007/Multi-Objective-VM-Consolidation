package vmconsolidation;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyAbstract;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.*;
import org.cloudsimplus.core.events.SimEvent;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.*;
import org.cloudsimplus.provisioners.ResourceProvisionerSimple;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.MipsShare;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.*;
import org.cloudsimplus.vms.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CloudSim Plus execution, demand scheduling, native migrations and measured accounting. */
public final class SimulationRun {
    static final double START = 1; // Exclude broker/VM startup from the measured horizon.
    static final int TICK = 70001, NEXT_MOVE = 70002;

    public enum Policy {
        NSGA2,
        THR_MMT_PABFD,
        STATIC
    }

    final Scenario scenario;
    final Config config;
    final Policy policy;
    final boolean baseline;
    final CloudSimPlus simulation = new CloudSimPlus(0.00001);
    final List<Host> hosts = new ArrayList<>();
    final List<Vm> vms = new ArrayList<>();
    final List<CloudletSimple> cloudlets = new ArrayList<>();
    final Random evolution;
    final double[] observedDemand, lastMigrationFinish;
    final Path output;
    final Metrics metrics;
    final Csv fronts, decisions, migrations, workload, snapshots, checks;
    final DataCenter datacenter;
    final Controller controller;
    int[] target;
    int migrating = -1, from = -1, to = -1;
    double migrationStart;
    int started, completed, blocked, decisionsCount, emptyFronts;
    long cloudletMiAtStart, cloudletMiAtEnd;

    public SimulationRun(Config config, Path output) throws IOException {
        this(config, output, Policy.NSGA2);
    }

    public SimulationRun(Config config, Path output, boolean baseline) throws IOException {
        this(config, output, baseline ? Policy.THR_MMT_PABFD : Policy.NSGA2);
    }

    public SimulationRun(Config config, Path output, Policy policy) throws IOException {
        this.policy = policy;
        this.baseline = (policy == Policy.THR_MMT_PABFD);
        this.config = config;
        this.output = output;
        scenario = new Scenario(config);
        evolution = new Random(config.seed() ^ 0x6a09e667f3bcc909L);
        observedDemand = scenario.demand(0);
        lastMigrationFinish = new double[config.vms()];
        Arrays.fill(lastMigrationFinish, Double.NEGATIVE_INFINITY);
        checks =
                new Csv(
                        output.resolve("decision_checks.csv"),
                        "time_s",
                        "reason",
                        "apply",
                        "stay_energy_kwh",
                        "stay_risk",
                        "stay_carbon_kg",
                        "candidate_energy_kwh",
                        "candidate_risk",
                        "candidate_carbon_kg");
        fronts =
                new Csv(
                        output.resolve("pareto_fronts.csv"),
                        "time_s",
                        "candidate",
                        "selected",
                        "energy_kwh",
                        "performance_risk",
                        "carbon_kg",
                        "planned_migrations",
                        "placement");
        decisions =
                new Csv(
                        output.resolve("decisions.csv"),
                        "time_s",
                        "front_size",
                        "proposed_moves",
                        "search_ms",
                        "status",
                        "placement");
        migrations =
                new Csv(
                        output.resolve("migrations.csv"),
                        "vm",
                        "source",
                        "destination",
                        "start_s",
                        "finish_s",
                        "duration_s",
                        "status");
        workload =
                new Csv(
                        output.resolve("workload.csv"),
                        "time_s",
                        "vm",
                        "requested_mips",
                        "utilization");
        snapshots =
                new Csv(
                        output.resolve("snapshots.csv"),
                        "time_s",
                        "vm",
                        "host",
                        "requested_mips",
                        "can_migrate",
                        "last_migration_s");
        for (var spec : scenario.hosts) {
            HostSimple h =
                    new HostSimple(
                            spec.ram(),
                            (long) Scenario.HOST_BW,
                            1_000_000,
                            List.of(new PeSimple(spec.peMips()), new PeSimple(spec.peMips()))) {
                        @Override
                        public void removeMigratingInVm(Vm vm) {
                            // 8.0.0 reserves destination disk on arrival, then allocates it again
                            // on completion.
                            if (getVmsMigratingIn().contains(vm))
                                ((org.cloudsimplus.resources.HarddriveStorage) getStorage())
                                        .getStorage()
                                        .deallocateResource(vm.getStorage().getCapacity());
                            super.removeMigratingInVm(vm);
                        }
                    };
            h.setRamProvisioner(new LocalReservations());
            h.setBwProvisioner(new LocalReservations());
            h.setId(spec.id());
            h.setVmScheduler(new DemandScheduler());
            h.setIdleShutdownDeadline(-1); // Controller owns the explicit ideal-sleep model.
            hosts.add(h);
        }
        int[] noPlacement = new int[config.vms()];
        Arrays.fill(noPlacement, -1);
        var initialProblem = new PlacementProblem(scenario, observedDemand, noPlacement, 0);
        int[] initial = initialProblem.repair(noPlacement, new Random(config.seed() ^ 73));
        if (initial == null)
            throw new IllegalArgumentException(
                    "No feasible initial arrangement; increase hosts or reduce VMs.");
        var allocation =
                new VmAllocationPolicyAbstract() {
                    @Override
                    protected Optional<Host> defaultFindHostForVm(Vm vm) {
                        return Optional.of(hosts.get(initial[(int) vm.getId()]));
                    }
                };
        datacenter = new DataCenter(allocation);
        datacenter.setSchedulingInterval(config.sample());
        datacenter.setBandwidthPercentForMigration(Scenario.MIGRATION_FRACTION);
        var broker = new DatacenterBrokerSimple(simulation);
        for (var spec : scenario.vms) {
            Vm vm = new VmSimple(spec.id(), spec.mips(), 1);
            vm.setRam(spec.ram())
                    .setBw((long) Scenario.VM_BW)
                    .setSize(1000)
                    .setCloudletScheduler(new CloudletSchedulerTimeShared());
            vms.add(vm);
            var cpu =
                    new UtilizationModelAbstract() {
                        @Override
                        protected double getUtilizationInternal(double time) {
                            return observedDemand[spec.id()] / spec.mips();
                        }
                    };
            CloudletSimple cl = new CloudletSimple(spec.id(), 1_000_000_000_000L, 1);
            cl.setUtilizationModelCpu(cpu)
                    .setUtilizationModelRam(new UtilizationModelFull())
                    .setUtilizationModelBw(new UtilizationModelFull());
            cl.setVm(vm);
            cloudlets.add(cl);
        }
        broker.submitVmList(vms);
        broker.submitCloudletList(cloudlets);
        metrics = new Metrics();
        controller = new Controller();
    }

    public Map<String, Double> run() throws IOException {
        long start = System.nanoTime();
        try {
            simulation.start();
            if (Math.abs(metrics.lastTime - config.duration()) > 1e-6)
                throw new IllegalStateException("Horizon not completed");
            if (vms.stream().anyMatch(v -> v.getHost() == Host.NULL))
                throw new IllegalStateException("A VM lost its host");
            var summary = metrics.summary();
            summary.put("wall_seconds", (System.nanoTime() - start) / 1e9);
            try (var csv =
                    new Csv(
                            output.resolve("summary.csv"),
                            summary.keySet().toArray(String[]::new))) {
                csv.row(summary.values().toArray());
            }
            return summary;
        } finally {
            fronts.close();
            decisions.close();
            migrations.close();
            workload.close();
            snapshots.close();
            checks.close();
            metrics.close();
        }
    }

    double time() {
        return Math.clamp(simulation.clock() - START, 0, config.duration());
    }

    int[] placement() {
        int[] p = new int[vms.size()];
        for (int i = 0; i < p.length; i++) p[i] = (int) vms.get(i).getHost().getId();
        return p;
    }

    void refresh() {
        for (var h : hosts) {
            h.setActive(!h.getVmList().isEmpty() || !h.getVmsMigratingIn().isEmpty());
            h.updateProcessing(simulation.clock());
        }
    }

    /**
     * Keep simultaneous source/destination reservations separate. The 8.0.0 default provisioner
     * reads a single mutable VM resource object, shared by both hosts.
     */
    static final class LocalReservations extends ResourceProvisionerSimple {
        private final Map<Vm, Long> allocations = new HashMap<>();

        @Override
        public long getAllocatedResourceForVm(Vm vm) {
            return allocations.getOrDefault(vm, 0L);
        }

        @Override
        public boolean allocateResourceForVm(Vm vm, long amount) {
            if (!isSuitableForVm(vm, amount)) return false;
            long delta = amount - getAllocatedResourceForVm(vm);
            if (delta >= 0) getPmResource().allocateResource(delta);
            else getPmResource().deallocateResource(-delta);
            allocations.put(vm, amount);
            getVmResourceFunction().apply(vm).setAllocatedResource(amount);
            return true;
        }

        @Override
        public long deallocateResourceForVm(Vm vm) {
            long amount = getAllocatedResourceForVm(vm);
            allocations.remove(vm);
            getPmResource().deallocateResource(amount);
            return amount;
        }
    }

    /**
     * Demand-aware time sharing is the only custom scheduler extension. Native TimeShared reserves
     * nominal MIPS and does not allow oversubscription. Here delivered capacity scales
     * proportionally only when observed demand exceeds capacity. The cloudlet utilization fraction
     * is applied by CloudSim; do not apply it twice.
     */
    final class DemandScheduler extends VmSchedulerTimeShared {
        DemandScheduler() {
            super(Scenario.MIGRATION_LOSS);
        }

        Set<Vm> residents() {
            Set<Vm> all = new LinkedHashSet<>(getHost().getVmList());
            all.addAll(getHost().getVmsMigratingIn());
            return all;
        }

        double demand(Vm vm) {
            return observedDemand[(int) vm.getId()];
        }

        @Override
        protected boolean isSuitableForVmInternal(Vm vm, MipsShare requested) {
            double reserved =
                    residents().stream().filter(v -> v != vm).mapToDouble(this::demand).sum();
            // A previously admitted migration owns destination reservations. Demand may have
            // risen during transfer: complete it and time-share, rather than losing the VM.
            boolean committed =
                    getHost().getRamProvisioner().getAllocatedResourceForVm(vm) > 0
                            && vm.getHost() != getHost()
                            && !vm.isCreated();
            return vm.getMips() <= getPeCapacity() + 1e-8
                    && requested.pes() <= getHost().getWorkingPesNumber()
                    && (committed
                            || reserved + demand(vm) <= getHost().getTotalMipsCapacity() + 1e-8);
        }

        @Override
        public MipsShare getAllocatedMips(Vm vm) {
            double load = 0;
            for (Vm v : residents())
                load +=
                        demand(v)
                                * (getHost().getVmsMigratingIn().contains(v)
                                        ? Scenario.MIGRATION_LOSS
                                        : 1);
            double scale = Math.min(1, getHost().getTotalMipsCapacity() / Math.max(1e-12, load));
            double role =
                    getHost().getVmsMigratingIn().contains(vm)
                            ? Scenario.MIGRATION_LOSS
                            : getHost().getVmsMigratingOut().contains(vm)
                                    ? 1 - Scenario.MIGRATION_LOSS
                                    : 1;
            return new MipsShare(vm.getPesNumber(), vm.getMips() * scale * role);
        }

        @Override
        public double getTotalAvailableMips() {
            return Math.max(
                    0,
                    getHost().getTotalMipsCapacity()
                            - residents().stream().mapToDouble(this::demand).sum());
        }
    }

    final class DataCenter extends DatacenterSimple {
        DataCenter(VmAllocationPolicyAbstract allocation) {
            super(simulation, hosts, allocation);
        }

        @Override
        protected boolean finishVmMigration(SimEvent event, boolean ack) {
            metrics.advance(time());
            boolean result = super.finishVmMigration(event, ack);
            if (migrating < 0 || (int) vms.get(migrating).getHost().getId() != to)
                throw new IllegalStateException("Migration did not reach its target");
            migrations.row(
                    migrating,
                    from,
                    to,
                    migrationStart,
                    time(),
                    time() - migrationStart,
                    "completed");
            completed++;
            lastMigrationFinish[migrating] = time();
            migrating = -1;
            refresh();
            metrics.capture();
            controller.schedule(0.00002, NEXT_MOVE);
            return result;
        }
    }

    final class Controller extends CloudSimEntity {
        Controller() {
            super(simulation);
        }

        @Override
        protected void startInternal() {
            schedule(START, TICK);
        }

        @Override
        public void processEvent(SimEvent event) {
            if (event.getTag() == NEXT_MOVE) {
                startNextMove();
                return;
            }
            // Lifecycle events are not workload samples and must not reopen measurement.
            if (event.getTag() != TICK) return;
            double t = time();
            metrics.advance(t);
            // Finish previous constant-demand interval before publishing the new observation.
            refresh();
            if (t >= config.duration() - 1e-8) {
                cloudletMiAtEnd =
                        cloudlets.stream().mapToLong(c -> c.getFinishedLengthSoFar()).sum();
                metrics.capture();
                target = null;
                // Let CloudSim process its broker/VM shutdown messages while still running.
                // terminate() clears that flag immediately and causes rejected-send warnings.
                getSimulation()
                        .terminateAt(
                                getSimulation().clock()
                                        + getSimulation().getMinTimeBetweenEvents());
                return;
            }
            if (vms.stream().anyMatch(v -> !v.isCreated()))
                throw new IllegalStateException("Not all VMs were created");
            if (cloudlets.stream().anyMatch(c -> c.getExecStartTime() < 0))
                throw new IllegalStateException("Workload did not start");
            if (t == 0)
                cloudletMiAtStart =
                        cloudlets.stream().mapToLong(c -> c.getFinishedLengthSoFar()).sum();
            double[] next = scenario.demand(t);
            System.arraycopy(next, 0, observedDemand, 0, next.length);
            refresh();
            for (int i = 0; i < vms.size(); i++)
                workload.row(
                        t, i, observedDemand[i], observedDemand[i] / scenario.vms.get(i).mips());
            if (Math.round(t) % config.epoch() == 0) {
                if (migrating < 0) {
                    target = null;
                    optimize(t);
                } else
                    decisions.row(
                            t,
                            0,
                            0,
                            0,
                            "epoch_skipped_migration_in_progress",
                            Csv.placement(placement()));
            }
            metrics.capture();
            schedule(Math.min(config.sample(), config.duration() - t), TICK);
        }
    }

    /** Plan at an epoch; the migration guard decides whether a recommendation is executed. */
    void optimize(double t) {
        int[] current = placement();
        boolean[] allowed =
                PlacementProblem.movementAllowed(
                        scenario, observedDemand, current, lastMigrationFinish, t);
        for (int i = 0; i < current.length; i++)
            snapshots.row(t, i, current[i], observedDemand[i], allowed[i], lastMigrationFinish[i]);
        if (policy == Policy.STATIC) {
            decisionsCount++;
            decisions.row(t, 0, 0, 0, "static_no_migration", Csv.placement(current));
            target = null;
            return;
        }
        var problem = new PlacementProblem(scenario, observedDemand, current, t, allowed);
        if (baseline) {
            long begin = System.nanoTime();
            target = vmconsolidation.baseline.ThrMmtPabfd.plan(problem);
            decisionsCount++;
            decisions.row(
                    t,
                    0,
                    Nsga2.moves(target, current),
                    (System.nanoTime() - begin) / 1e6,
                    "baseline_target",
                    Csv.placement(target));
            startNextMove();
            return;
        }
        long begin = System.nanoTime();
        var front = Nsga2.search(problem, evolution);
        double elapsed = (System.nanoTime() - begin) / 1e6;
        decisionsCount++;
        if (front.isEmpty()) {
            emptyFronts++;
            decisions.row(t, 0, 0, elapsed, "no_feasible_candidate", Csv.placement(current));
            return;
        }
        var decision = MigrationDecision.choose(front, problem);
        var chosen = decision.candidate();
        var stay = problem.stayForecast();
        checks.row(
                t,
                decision.reason(),
                decision.apply(),
                stay[0],
                stay[1],
                stay[2],
                chosen.objectives[0],
                chosen.objectives[1],
                chosen.objectives[2]);
        for (int i = 0; i < front.size(); i++) {
            var c = front.get(i);
            if (!problem.feasible(c.placement))
                throw new IllegalStateException("Invalid Pareto candidate");
            fronts.row(
                    t,
                    i,
                    c == chosen,
                    c.objectives[0],
                    c.objectives[1],
                    c.objectives[2],
                    Nsga2.moves(c.placement, current),
                    Csv.placement(c.placement));
        }
        target = decision.apply() ? chosen.placement.clone() : current.clone();
        decisions.row(
                t,
                front.size(),
                Nsga2.moves(target, current),
                elapsed,
                decision.apply() ? "selected_target" : "hold_current",
                Csv.placement(target));
        startNextMove();
    }

    /** Start at most one native transfer, retaining reservations at both endpoints. */
    void startNextMove() {
        if (target == null || migrating >= 0) return;
        metrics.advance(time());
        refresh();
        int[] current = placement();
        for (int i = 0; i < vms.size(); i++)
            if (current[i] != target[i]) {
                Vm vm = vms.get(i);
                Host dest = hosts.get(target[i]);
                if (time() + Scenario.migrationSeconds(scenario.vms.get(i)) > config.duration())
                    continue;
                dest.setActive(true);
                if (!dest.isSuitableForVm(vm)) {
                    dest.setActive(!dest.getVmList().isEmpty());
                    continue;
                }
                migrating = i;
                from = current[i];
                to = target[i];
                migrationStart = time();
                datacenter.requestVmMigration(vm, dest);
                if (!vm.isInMigration())
                    throw new IllegalStateException("Migration admission unexpectedly failed");
                started++;
                refresh();
                metrics.capture();
                return;
            }
        int remaining = Nsga2.moves(target, current);
        blocked += remaining;
        decisions.row(
                time(),
                0,
                remaining,
                0,
                remaining == 0 ? "target_completed" : "target_partially_applied",
                Csv.placement(current));
        target = null;
        refresh();
        metrics.capture();
    }

    /** Piecewise-constant integration split at workload changes and native migration events. */
    final class Metrics implements AutoCloseable {
        final Csv hostCsv, zoneCsv;
        final double[] activeTime = new double[config.hosts()],
                saturatedTime = new double[config.hosts()];
        final double[] requestedMi = new double[config.vms()],
                deliveredMi = new double[config.vms()],
                migrationLostMi = new double[config.vms()];
        double[] hostPower = new double[config.hosts()],
                hostDemand = new double[config.hosts()],
                hostDelivered = new double[config.hosts()];
        double[] allocated = new double[config.vms()], demand = new double[config.vms()];
        boolean[] active = new boolean[config.hosts()];
        boolean migratingNow;
        int migrationVm = -1;
        double lastTime,
                itEnergy,
                facilityEnergy,
                gridEnergy,
                carbon,
                activeHostSeconds,
                migrationTime;

        Metrics() throws IOException {
            hostCsv =
                    new Csv(
                            output.resolve("host_metrics.csv"),
                            "start_s",
                            "end_s",
                            "host",
                            "zone",
                            "active",
                            "requested_mips",
                            "delivered_mips",
                            "power_w",
                            "energy_kwh");
            zoneCsv =
                    new Csv(
                            output.resolve("zone_metrics.csv"),
                            "start_s",
                            "end_s",
                            "zone",
                            "it_kwh",
                            "facility_kwh",
                            "grid_kwh",
                            "carbon_kg",
                            "ci_g_per_kwh");
        }

        void capture() {
            Arrays.fill(hostDemand, 0);
            Arrays.fill(hostDelivered, 0);
            demand = observedDemand.clone();
            for (int i = 0; i < vms.size(); i++) {
                Vm vm = vms.get(i);
                int h = (int) vm.getHost().getId();
                allocated[i] = vm.getTotalCpuMipsUtilization();
                if (!Double.isFinite(allocated[i]) || allocated[i] > demand[i] + 1e-5)
                    throw new IllegalStateException("Invalid allocation");
                hostDemand[h] += demand[i];
                hostDelivered[h] += allocated[i];
            }
            migratingNow = migrating >= 0;
            migrationVm = migrating;
            // Account for migration CPU work as well as delivered application CPU.
            if (migratingNow) {
                hostDelivered[from] +=
                        allocated[migrating]
                                * Scenario.MIGRATION_LOSS
                                / (1 - Scenario.MIGRATION_LOSS);
                hostDelivered[to] +=
                        hosts.get(to)
                                        .getVmScheduler()
                                        .getAllocatedMips(vms.get(migrating))
                                        .totalMips()
                                * demand[migrating]
                                / scenario.vms.get(migrating).mips();
                hostDemand[to] += demand[migrating] * Scenario.MIGRATION_LOSS;
            }
            for (int j = 0; j < hosts.size(); j++) {
                Set<Vm> residents = new HashSet<>(hosts.get(j).getVmList());
                residents.addAll(hosts.get(j).getVmsMigratingIn());
                long expectedRam =
                        residents.stream().mapToLong(v -> v.getRam().getCapacity()).sum();
                long expectedBw = (long) (residents.size() * Scenario.VM_BW);
                if (expectedRam > scenario.hosts.get(j).ram()
                        || expectedBw > Scenario.HOST_BW
                        || hosts.get(j).getRam().getAllocatedResource() != expectedRam
                        || hosts.get(j).getBw().getAllocatedResource() != expectedBw
                        || hosts.get(j).getStorage().getAllocatedResource()
                                != residents.size() * 1000L)
                    throw new IllegalStateException(
                            "Host resource accounting mismatch on host " + j);
                active[j] = hosts.get(j).isActive();
                hostPower[j] =
                        active[j]
                                ? scenario.hosts
                                        .get(j)
                                        .power(hostDelivered[j] / scenario.hosts.get(j).capacity())
                                : 0;
            }
        }

        void advance(double t) {
            double dt = t - lastTime;
            if (dt <= 1e-9) return;
            double[] zones = new double[4];
            for (int j = 0; j < hosts.size(); j++) {
                double e = hostPower[j] * dt / 3_600_000;
                int z = scenario.hosts.get(j).zone();
                zones[z] += e;
                itEnergy += e;
                if (active[j]) {
                    activeTime[j] += dt;
                    activeHostSeconds += dt;
                    if (hostDemand[j] >= scenario.hosts.get(j).capacity() - 1e-8)
                        saturatedTime[j] += dt;
                }
                hostCsv.row(
                        lastTime,
                        t,
                        j,
                        Scenario.ZONES[z],
                        active[j],
                        hostDemand[j],
                        hostDelivered[j],
                        hostPower[j],
                        e);
            }
            // Environmental signals are observed at workload sample boundaries and held per sample.
            double envTime = Math.floor((lastTime + 1e-7) / config.sample()) * config.sample();
            for (int z = 0; z < 4; z++) {
                double facility = zones[z] * Scenario.PUE[z],
                        grid =
                                Scenario.gridEnergy(
                                        zones[z],
                                        Scenario.PUE[z],
                                        scenario.renewableKw(z, envTime) * dt / 3600);
                double ci = Scenario.carbonIntensity(z, envTime), c = grid * ci / 1000;
                facilityEnergy += facility;
                gridEnergy += grid;
                carbon += c;
                zoneCsv.row(lastTime, t, Scenario.ZONES[z], zones[z], facility, grid, c, ci);
            }
            for (int i = 0; i < vms.size(); i++) {
                requestedMi[i] += demand[i] * dt;
                deliveredMi[i] += allocated[i] * dt;
            }
            if (migratingNow) {
                migrationTime += dt;
                migrationLostMi[migrationVm] += Scenario.MIGRATION_LOSS * demand[migrationVm] * dt;
            }
            lastTime = t;
        }

        Map<String, Double> summary() {
            double slatah = 0, pdm = 0;
            for (int j = 0; j < hosts.size(); j++)
                if (activeTime[j] > 0) slatah += saturatedTime[j] / activeTime[j] / hosts.size();
            for (int i = 0; i < vms.size(); i++)
                if (requestedMi[i] > 0) pdm += migrationLostMi[i] / requestedMi[i] / vms.size();
            double req = Arrays.stream(requestedMi).sum(),
                    del = Arrays.stream(deliveredMi).sum(),
                    slav = slatah * pdm;
            var s = new LinkedHashMap<String, Double>();
            s.put("seed", (double) config.seed());
            s.put("duration_s", lastTime);
            s.put("it_energy_kwh", itEnergy);
            s.put("facility_energy_kwh", facilityEnergy);
            s.put("grid_energy_kwh", gridEnergy);
            s.put("carbon_kg", carbon);
            s.put("slatah", slatah);
            s.put("pdm_model", pdm);
            s.put("slav", slav);
            s.put("esv", itEnergy * slav);
            s.put("cpu_shortfall_fraction", Math.max(0, req - del) / Math.max(1e-12, req));
            s.put("requested_mi", req);
            s.put("delivered_mi", del);
            s.put("mean_active_hosts", activeHostSeconds / lastTime);
            s.put(
                    "mean_requested_cluster_utilization",
                    req
                            / (lastTime
                                    * scenario.hosts.stream()
                                            .mapToDouble(Scenario.HostSpec::capacity)
                                            .sum()));
            s.put("migrations_started", (double) started);
            s.put("migrations_completed", (double) completed);
            s.put("migration_seconds", migrationTime);
            s.put("blocked_target_moves", (double) blocked);
            s.put("decisions", (double) decisionsCount);
            s.put("empty_fronts", (double) emptyFronts);
            s.put("cloudlet_executed_mi", (double) (cloudletMiAtEnd - cloudletMiAtStart));
            return s;
        }

        public void close() throws IOException {
            hostCsv.close();
            zoneCsv.close();
        }
    }
}
