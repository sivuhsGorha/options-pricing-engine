package com.sbk.optionspricer.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.format.DateTimeFormatter;

/**
 * An append-only record of what was done to the running engine through its control API: each halt, resume, strategy switch and supervised test
 * order, and each attempt that was refused. One CSV row per action (time, action, outcome, client address, detail), kept in {@code data/} beside the
 * decision journal. It never holds a secret: the detail is the operator's own reason or the setting, not a credential. A log that cannot be written
 * is reported once and never stops the control from working: auditing is not allowed to become a way to lock the operator out of a halt.
 */
public final class ControlAuditLog {

    private static final String HEADER = "timestamp,action,outcome,client,detail";
    private static final int MAX_DETAIL = 200;

    private final Path file;
    private final Clock clock;
    private boolean warned;

    public ControlAuditLog(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    public synchronized void record(String action, String outcome, String client, String detail) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            StringBuilder row = new StringBuilder();
            if (!Files.exists(file) || Files.size(file) == 0L) {
                row.append(HEADER).append('\n');
            }
            row.append(DateTimeFormatter.ISO_INSTANT.format(clock.instant())).append(',')
                    .append(field(action)).append(',').append(field(outcome)).append(',')
                    .append(field(client)).append(',').append(field(detail)).append('\n');
            Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            if (!warned) {
                warned = true;
                System.err.println("[AUDIT] the control audit log cannot be written (" + e.getClass().getSimpleName() + "); controls still work");
            }
        }
    }

    /** One CSV field: newlines become spaces (so a value cannot start another row), long text is cut, and a field with a comma or quote is quoted. */
    static String field(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\r', ' ').replace('\n', ' ');
        if (flat.length() > MAX_DETAIL) {
            flat = flat.substring(0, MAX_DETAIL);
        }
        if (flat.contains(",") || flat.contains("\"")) {
            return "\"" + flat.replace("\"", "\"\"") + "\"";
        }
        return flat;
    }
}
