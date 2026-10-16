package dev.kivts.cide.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory ring buffer backing the collapsible log drawer (spec section 4.3).
 * The drawer UI renders snapshots of this list; the drawer itself lands in phase 3.
 * All levels follow the spec's log event standard:
 *   [INFO] connection events, handshakes, port bindings
 *   [SYNC] file payload saves received from VS Code
 *   [PERIPHERAL] peripheral change events and API scraping updates
 *   [WARN] rejected file saves targeting inactive computers
 *   [ERROR] caught exceptions
 */
public final class BridgeLog {
    public enum Level { INFO, SYNC, PERIPHERAL, WARN, ERROR }

    public record Entry(Level level, String timestamp, String message) {}

    private static final int MAX_LINES = 500;
    private static final int MAX_TRACE_FRAMES = 12;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final List<Entry> ENTRIES = Collections.synchronizedList(new ArrayList<>());
    // Mirrors every entry to the game console/log file so bridge activity is
    // observable before the log drawer UI exists (phase 3).
    private static final Logger LOGGER = LoggerFactory.getLogger("CC-Studio Bridge");

    private BridgeLog() {}

    public static void log(Level level, String message) {
        record(level, message);
        mirror(level, message, null);
    }

    public static void info(String message) {
        log(Level.INFO, message);
    }

    public static void sync(String message) {
        log(Level.SYNC, message);
    }

    public static void peripheral(String message) {
        log(Level.PERIPHERAL, message);
    }

    public static void warn(String message) {
        log(Level.WARN, message);
    }

    /**
     * Logs a caught exception with its message and a flattened stack trace so the
     * drawer can display it as plain text (spec section 2.2: failure traces route
     * to the log drawer, never to a crash).
     */
    public static void error(String message, Throwable throwable) {
        if (throwable == null) {
            log(Level.ERROR, message);
            return;
        }
        // Drawer gets the flattened form; the console gets the full stack once.
        StringBuilder sb = new StringBuilder(message);
        sb.append(" - ").append(throwable.getClass().getName());
        if (throwable.getMessage() != null && !throwable.getMessage().isBlank()) {
            sb.append(": ").append(throwable.getMessage());
        }
        StackTraceElement[] trace = throwable.getStackTrace();
        for (int i = 0; i < Math.min(trace.length, MAX_TRACE_FRAMES); i++) {
            sb.append("\n    at ").append(trace[i].toString());
        }
        if (trace.length > MAX_TRACE_FRAMES) {
            sb.append("\n    ... ").append(trace.length - MAX_TRACE_FRAMES).append(" more");
        }
        record(Level.ERROR, sb.toString());
        mirror(Level.ERROR, message, throwable);
    }

    private static void record(Level level, String message) {
        synchronized (ENTRIES) {
            ENTRIES.add(new Entry(level, LocalTime.now().format(TS), message));
            while (ENTRIES.size() > MAX_LINES) ENTRIES.remove(0);
        }
    }

    private static void mirror(Level level, String message, Throwable throwable) {
        // No level tag here: the log4j2 console pattern already prints it.
        switch (level) {
            case WARN -> LOGGER.warn(message);
            case ERROR -> {
                if (throwable != null) {
                    LOGGER.error(message, throwable);
                } else {
                    LOGGER.error(message);
                }
            }
            default -> LOGGER.info(message);
        }
    }

    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    public static List<Entry> snapshot() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }
}
