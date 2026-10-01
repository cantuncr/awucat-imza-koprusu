package app.awucat.signer;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tiny logger writing to stdout and to {@code ~/.awucat-signer/signer.log} (rotated once at
 * 1 MB). Callers must never pass PINs or data-to-be-signed to this class.
 */
final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long MAX_BYTES = 1L << 20;
    private static Path file;

    private Log() {}

    static synchronized void init(Path logFile) {
        try {
            Files.createDirectories(logFile.getParent());
            if (Files.exists(logFile) && Files.size(logFile) > MAX_BYTES) {
                Files.move(logFile, logFile.resolveSibling(logFile.getFileName() + ".1"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            file = logFile;
        } catch (IOException e) {
            System.err.println("Günlük dosyası açılamadı: " + e);
        }
    }

    static void info(String msg) { write("INFO ", msg); }

    static void warn(String msg) { write("WARN ", msg); }

    /** Logs an unexpected failure with its stack trace. */
    static void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        write("ERROR", msg + (t == null ? "" : "\n" + sw.toString().trim()));
    }

    private static synchronized void write(String level, String msg) {
        String line = LocalDateTime.now().format(TS) + " " + level + " " + msg;
        System.out.println(line);
        if (file == null) return;
        try {
            Files.write(file, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // stdout already has the line; a broken log file must not break signing.
        }
    }
}
