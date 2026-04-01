package com.acme.fta;

import java.io.PrintStream;
import java.util.Locale;

public final class DebugLogger {
    private static final String PREFIX = "[DEBUG";

    private final boolean enabled;
    private final long startedAtNanos;
    private final PrintStream stream;

    public DebugLogger(boolean enabled) {
        this(enabled, System.err);
    }

    public DebugLogger(boolean enabled, PrintStream stream) {
        this.enabled = enabled;
        this.startedAtNanos = System.nanoTime();
        this.stream = stream;
    }

    public boolean enabled() {
        return enabled;
    }

    public void log(String message, Object... args) {
        if (!enabled) {
            return;
        }
        String rendered = args == null || args.length == 0
                ? message
                : String.format(Locale.ROOT, message, args);
        double seconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
        synchronized (stream) {
            stream.printf(Locale.ROOT, "%s +%7.3fs] %s%n", PREFIX, seconds, rendered);
            stream.flush();
        }
    }
}
