package vmconsolidation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vmconsolidation.baseline.BaselineMain;

import java.nio.file.Files;
import java.nio.file.Path;

class MainTest {
    @TempDir Path temp;

    @Test
    void partialRunWithoutManifestIsNotOverwritten() throws Exception {
        Path workload = temp.resolve("workload.csv");
        Files.writeString(workload, "keep existing measurements");
        assertThrows(
                IllegalArgumentException.class, () -> Main.main(new String[] {"output=" + temp}));
        assertEquals("keep existing measurements", Files.readString(workload));
        assertFalse(Files.exists(temp.resolve("manifest.properties")));
    }

    @Test
    void blankOutputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Main.main(new String[] {"output= "}));
    }

    @Test
    void baselineEntryPointAlwaysRunsTheHeuristic() throws Exception {
        Path output = temp.resolve("baseline");
        BaselineMain.main(
                new String[] {
                    "policy=nsga2",
                    "hosts=4",
                    "vms=4",
                    "duration=60",
                    "workload=low",
                    "output=" + output
                });
        String manifest = Files.readString(output.resolve("manifest.properties"));
        assertTrue(manifest.startsWith("status=complete\n"));
        assertTrue(manifest.contains("\npolicy=thr_mmt_pabfd\n"));
        assertEquals(1, Files.readAllLines(output.resolve("pareto_fronts.csv")).size());
    }
}
