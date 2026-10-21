package com.sbk.optionspricer.logging;

import com.sbk.optionspricer.config.EnvironmentConfigLoader;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place everything the engine prints passes through before it leaves the process. It does two things.
 *
 * <p><b>It removes secrets.</b> The exact value of every configured secret (the broker keys, the API secret, the operator password, the alert topic and
 * webhook, the heartbeat URL, the keystore password) is replaced by {@code ***} wherever it appears in a line. The engine has hundreds of places that
 * print, and some print an exception's message, which can quote a URL or a key; this does not rely on each of them being written carefully. It works on
 * whole lines, so a secret printed in pieces is still caught, and on stack traces line by line.
 *
 * <p><b>It can structure the output.</b> With {@code LOG_FORMAT=json} each line becomes one JSON object (timestamp, level, component, message, stream) that
 * log tools can parse. The component is the leading {@code [TAG]}; the level is INFO on standard output, WARN on standard error and ERROR when the line
 * says so (fatal, urgent, a halt, an exception). Without it the lines are unchanged apart from the redaction.
 */
public final class ConsoleGuard {

    /** A value shorter than this is not treated as a secret: replacing it would mangle ordinary words. */
    static final int MIN_SECRET_LENGTH = 8;

    private static final List<String> SECRET_VARIABLES = List.of(
            "ALPACA_SECRET", "ALPACA_KEY_ID", "API_SECRET", "OPERATOR_PASSWORD", "NTFY_TOPIC", "ALERT_WEBHOOK_URL", "HEARTBEAT_URL",
            "FINNHUB_KEY", "ALPHA_VANTAGE_KEY", "POLYGON_API_KEY", "MARKETSTACK_KEY", "TLS_KEYSTORE_PASSWORD");

    private static final Pattern TAG = Pattern.compile("^\\[([^\\]]+)\\]\\s*(.*)$");
    private static final Pattern ERROR_WORDS = Pattern.compile("(?i)\\b(fatal|urgent|exception|error)\\b|^\\[TRADING HALT\\]");

    private static boolean installed;

    private ConsoleGuard() {}

    /** The values of the secret environment variables, those long enough to be worth replacing. */
    public static List<String> secretsFrom(Function<String, String> environment) {
        List<String> secrets = new ArrayList<>();
        for (String name : SECRET_VARIABLES) {
            String value = environment.apply(name);
            if (value != null && value.trim().length() >= MIN_SECRET_LENGTH) {
                secrets.add(value.trim());
            }
        }
        return secrets;
    }

    /** {@code text} with every occurrence of every secret replaced; the longest first, so a secret inside another leaves no fragment. */
    public static String redact(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty() || secrets == null) {
            return text;
        }
        List<String> ordered = new ArrayList<>();
        for (String secret : secrets) {
            if (secret != null && secret.trim().length() >= MIN_SECRET_LENGTH) {
                ordered.add(secret.trim());
            }
        }
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        String out = text;
        for (String secret : ordered) {
            out = out.replace(secret, "***");
        }
        return out;
    }

    /** A stream that redacts, and with {@code json} structures, each line written to {@code target}. */
    public static Guarded wrap(PrintStream target, String stream, Collection<String> secrets, boolean json, Clock clock) {
        return new Guarded(new LineStream(target, stream, List.copyOf(secrets), json, clock));
    }

    /** Wraps standard output and standard error for the life of the process; later calls do nothing. */
    public static synchronized void install(boolean json) {
        if (installed) {
            return;
        }
        installed = true;
        List<String> secrets = secretsFrom(EnvironmentConfigLoader::get);
        Guarded out = wrap(System.out, "stdout", secrets, json, Clock.systemUTC());
        Guarded err = wrap(System.err, "stderr", secrets, json, Clock.systemUTC());
        System.setOut(out);
        System.setErr(err);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            out.flushPending();
            err.flushPending();
        }, "console-guard-flush"));
    }

    /** The guarded stream. Lines are held until they end; {@link #flushPending()} writes one that has not. */
    public static final class Guarded extends PrintStream {
        private final LineStream lines;

        private Guarded(LineStream lines) {
            super(lines, true, StandardCharsets.UTF_8);
            this.lines = lines;
        }

        /** Writes a partly written last line (a process that ends mid-line). */
        public void flushPending() {
            lines.flushPending();
        }
    }

    private static final class LineStream extends OutputStream {
        private final PrintStream target;
        private final String stream;
        private final List<String> secrets;
        private final boolean json;
        private final Clock clock;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        LineStream(PrintStream target, String stream, List<String> secrets, boolean json, Clock clock) {
            this.target = target;
            this.stream = stream;
            this.secrets = secrets;
            this.json = json;
            this.clock = clock;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                pending.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                write(bytes[i]);
            }
        }

        synchronized void flushPending() {
            if (pending.size() > 0) {
                emit();
            }
        }

        private void emit() {
            String line = pending.toString(StandardCharsets.UTF_8);
            pending.reset();
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String safe = redact(line, secrets);
            target.println(json ? toJson(safe) : safe);
        }

        private String toJson(String line) {
            String component = "console";
            String message = line;
            Matcher m = TAG.matcher(line);
            if (m.matches()) {
                component = m.group(1);
                message = m.group(2);
            }
            String level = ERROR_WORDS.matcher(line).find() ? "ERROR" : "stderr".equals(stream) ? "WARN" : "INFO";
            return "{\"timestamp\":\"" + DateTimeFormatter.ISO_INSTANT.format(clock.instant()) + "\",\"level\":\"" + level + "\",\"component\":\""
                    + escape(component) + "\",\"message\":\"" + escape(message) + "\",\"stream\":\"" + stream + "\"}";
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
