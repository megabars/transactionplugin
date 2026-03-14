package com.txplugin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Sends completed {@link TransactionRecord}s to the IntelliJ plugin over a
 * persistent TCP connection (localhost:{port}).
 *
 * Protocol: one JSON object per line (newline-delimited JSON / NDJSON).
 *
 * If the connection is not yet established or drops, records are buffered
 * in memory (up to BUFFER_CAPACITY) and flushed when reconnected.
 */
public class SocketReporter {

    private static final Logger LOG = Logger.getLogger(SocketReporter.class.getName());
    private static final int BUFFER_CAPACITY = 1000;
    private static final int RECONNECT_DELAY_MS = 3_000;

    private static SocketReporter INSTANCE;

    private final int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<TransactionRecord> buffer = new ArrayDeque<>(BUFFER_CAPACITY);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tx-reporter");
        t.setDaemon(true);
        return t;
    });

    private volatile Socket socket;
    private volatile BufferedWriter writer;

    public static synchronized void init(int port) {
        if (INSTANCE == null) {
            INSTANCE = new SocketReporter(port);
            INSTANCE.startConnectionLoop();
        }
    }

    public static void send(TransactionRecord record) {
        if (INSTANCE != null) INSTANCE.enqueue(record);
    }

    private SocketReporter(int port) {
        this.port = port;
    }

    private void enqueue(TransactionRecord record) {
        synchronized (buffer) {
            if (buffer.size() >= BUFFER_CAPACITY) buffer.pollFirst(); // drop oldest
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
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void connectAndStream() throws Exception {
        try (Socket s = new Socket("127.0.0.1", port);
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"))) {

            socket = s;
            writer = w;
            LOG.info("Transaction reporter connected to plugin on port " + port);

            while (!Thread.currentThread().isInterrupted() && !s.isClosed()) {
                TransactionRecord record;
                synchronized (buffer) {
                    while (buffer.isEmpty()) {
                        buffer.wait(1000);
                        if (s.isClosed()) return;
                    }
                    record = buffer.pollFirst();
                }
                String json = mapper.writeValueAsString(record);
                w.write(json);
                w.newLine();
                w.flush();
            }
        } finally {
            socket = null;
            writer = null;
        }
    }
}
