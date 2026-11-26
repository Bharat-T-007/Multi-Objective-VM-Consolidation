package vmconsolidation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Tiny CSV writer; always escapes fields and uses locale-independent numbers. */
public final class Csv implements AutoCloseable {
    private final BufferedWriter writer;

    public Csv(Path path, String... header) throws IOException {
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        row((Object[]) header);
    }

    public void row(Object... fields) {
        try {
            StringJoiner line = new StringJoiner(",");
            for (Object field : fields)
                line.add("\"" + String.valueOf(field).replace("\"", "\"\"") + "\"");
            writer.write(line.toString());
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void close() throws IOException {
        writer.close();
    }

    public static String placement(int[] p) {
        return Arrays.toString(p);
    }
}
