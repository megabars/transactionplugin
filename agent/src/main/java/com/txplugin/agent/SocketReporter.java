package com.txplugin.agent;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Sends completed {@link TransactionRecord}s to the IntelliJ plugin over a
 * persistent TCP connection (localhost:{port}).
 *
 * Protocol: one JSON object per line (NDJSON).
 * Uses manual JSON serialization — no external dependencies — so this class
 * can safely be injected into the bootstrap classloader.
 */
public class SocketReporter {

    private static final Logger LOG = Logger.getLogger(SocketReporter.class.getName());
    private static final int BUFFER_CAPACITY  = 1000;
    private static final int RECONNECT_MS     = 3_000;

    private final AtomicLong droppedCount = new AtomicLong();

    private static SocketReporter INSTANCE;

    private final int port;
    private final Deque<TransactionRecord> buffer = new ArrayDeque<>(BUFFER_CAPACITY);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tx-reporter");
        t.setDaemon(true);
        return t;
    });

    public static synchronized void init(int port) {
        if (INSTANCE == null) {
            INSTANCE = new SocketReporter(port);
            INSTANCE.startConnectionLoop();
        }
    }

    public static void send(TransactionRecord record) {
        if (INSTANCE != null) INSTANCE.enqueue(record);
    }

    private SocketReporter(int port) { this.port = port; }

    private void enqueue(TransactionRecord record) {
        synchronized (buffer) {
            if (buffer.size() >= BUFFER_CAPACITY) {
                buffer.pollFirst();
                LOG.fine("[TX] Ring buffer full — dropped oldest record (total dropped: " + droppedCount.incrementAndGet() + ")");
            }
            buffer.addLast(record);
            buffer.notifyAll();
        }
    }

    private void startConnectionLoop() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    connectAndStream();
                } catch (Exception e) {
                    LOG.fine("Socket reporter disconnected: " + e.getMessage());
                }
                try { Thread.sleep(RECONNECT_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        });
    }

    private void connectAndStream() throws Exception {
        try (Socket s = new Socket("127.0.0.1", port);
             BufferedWriter w = new BufferedWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            LOG.fine("Transaction reporter connected to plugin on port " + port);

            while (!Thread.currentThread().isInterrupted() && !s.isClosed()) {
                TransactionRecord record;
                synchronized (buffer) {
                    while (buffer.isEmpty()) {
                        buffer.wait(1000);
                        if (s.isClosed()) return;
                    }
                    record = buffer.pollFirst();
                }
                w.write(toJson(record));
                w.newLine();
                w.flush();
            }
        }
    }

    // ── Manual JSON serialisation (no external dependencies) ─────────────────

    private static String toJson(TransactionRecord r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        str(sb,  "transactionId",  r.transactionId);
        str(sb,  "className",      r.className);
        str(sb,  "methodName",     r.methodName);
        str(sb,  "parameterTypes", r.parameterTypes);
        num(sb,  "lineNumber",     r.lineNumber);
        num(sb,  "startTimeMs",    r.startTimeMs);
        num(sb,  "endTimeMs",      r.endTimeMs);
        num(sb,  "durationMs",     r.durationMs);
        str(sb,  "status",         r.status);
        str(sb,  "propagation",    r.propagation);
        str(sb,  "isolationLevel", r.isolationLevel);
        bool(sb, "readOnly",       r.readOnly);
        num(sb,  "timeout",        r.timeout);
        num(sb,  "sqlQueryCount",  r.sqlQueryCount);
        num(sb,  "batchCount",     r.batchCount);
        arr(sb,  "sqlQueries",     r.sqlQueries);
        num(sb,  "insertCount",    r.insertCount);
        num(sb,  "updateCount",    r.updateCount);
        num(sb,  "deleteCount",    r.deleteCount);
        str(sb,  "threadName",     r.threadName);
        strNullable(sb, "exceptionType",    r.exceptionType);
        strNullable(sb, "exceptionMessage", r.exceptionMessage);
        strNullable(sb, "stackTrace",       r.stackTrace);
        // remove trailing comma before closing brace
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append('}');
        return sb.toString();
    }

    private static void str(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"")
          .append(value == null ? "" : escape(value))
          .append("\",");
    }

    private static void strNullable(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) sb.append("null");
        else sb.append('"').append(escape(value)).append('"');
        sb.append(',');
    }

    private static void num(StringBuilder sb, String key, long value) {
        sb.append('"').append(key).append("\":").append(value).append(',');
    }

    private static void bool(StringBuilder sb, String key, boolean value) {
        sb.append('"').append(key).append("\":").append(value).append(',');
    }

    private static void arr(StringBuilder sb, String key, List<String> values) {
        sb.append('"').append(key).append("\":[");
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escape(values.get(i))).append('"');
            }
        }
        sb.append("],");
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
