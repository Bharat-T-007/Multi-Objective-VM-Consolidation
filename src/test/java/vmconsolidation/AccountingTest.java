package vmconsolidation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class AccountingTest {
    @Test
    void independentHostReservationsSurviveMigration() {
        var source = new SimulationRun.LocalReservations();
        var destination = new SimulationRun.LocalReservations();
        var a = new org.cloudsimplus.resources.Ram(4096);
        var b = new org.cloudsimplus.resources.Ram(4096);
        source.setResources(a, v -> ((org.cloudsimplus.vms.VmSimple) v).getRam());
        destination.setResources(b, v -> ((org.cloudsimplus.vms.VmSimple) v).getRam());
        var vm = new org.cloudsimplus.vms.VmSimple(1000, 1);
        vm.setRam(1740);
        assertTrue(source.allocateResourceForVm(vm, 1740));
        assertTrue(destination.allocateResourceForVm(vm, 1740));
        assertEquals(1740, a.getAllocatedResource());
        assertEquals(1740, b.getAllocatedResource());
        source.deallocateResourceForVm(vm);
        assertEquals(0, a.getAllocatedResource());
        assertEquals(1740, b.getAllocatedResource());
        assertTrue(destination.allocateResourceForVm(vm, 1740));
        assertEquals(1740, b.getAllocatedResource());
    }

    @Test
    void powerInterpolationAndCarbonUnits() {
        Scenario s = new Scenario(new Config(4, 1, 600, 60, 300, 8, 4, 11, "medium", .8, 0));
        assertEquals(86, s.hosts.getFirst().power(0));
        assertEquals(87.7, s.hosts.getFirst().power(.05), 1e-9);
        assertEquals(117, s.hosts.getFirst().power(1));
        assertEquals(1, 1000 * 3600 / 3_600_000.0);
        assertEquals(1.2, Scenario.gridEnergy(1, 1.7, .5), 1e-9);
        assertEquals(.42, Scenario.gridEnergy(1, 1.7, .5) * 350 / 1000, 1e-9);
        assertEquals(0, Scenario.gridEnergy(1, 1.7, 2));
        assertEquals(27.84, Scenario.migrationSeconds(new Scenario.VmSpec(0, 1000, 1740)), 1e-9);
    }

    @Test
    void impossibleConfigFailsInsteadOfDroppingVms() {
        Scenario s = new Scenario(new Config(4, 100, 600, 60, 300, 8, 4, 11, "high", .8, 0));
        int[] proposed = new int[100];
        Arrays.fill(proposed, -1);
        assertNull(
                new PlacementProblem(s, s.demand(0), proposed, 0).repair(proposed, new Random(1)));
    }

    @Test
    void currentObservationIsCopiedAndWorkloadIsSeeded() {
        var cfg = new Config(4, 4, 600, 60, 300, 8, 4, 11, "medium", .8, 0);
        var s = new Scenario(cfg);
        var other = new Scenario(cfg);
        assertArrayEquals(s.demand(120), other.demand(120));
        double[] d = s.demand(0);
        var p = new PlacementProblem(s, d, new int[] {2, 3, 0, 1}, 0);
        double expected = d[0];
        d[0] = 0;
        assertEquals(expected, p.demand[0]);
    }
}
