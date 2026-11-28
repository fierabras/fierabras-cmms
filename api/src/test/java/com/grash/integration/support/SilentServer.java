package com.grash.integration.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * A TCP server on the loopback address that accepts every connection and never answers: a mail server or a webhook
 * destination that hangs. Nothing leaves the machine.
 * <p>
 * {@link #refuse()} closes the connections it holds and closes each later one as soon as it is accepted, so the
 * clients it held are released and the next ones fail at once. {@link #close()} stops it.
 */
public final class SilentServer implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> held = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger closedByClient = new AtomicInteger();
    private volatile boolean refusing;

    public SilentServer() {
        try {
            server = new ServerSocket(0, 100, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        daemon("silent-server-" + server.getLocalPort(), this::acceptAll).start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /**
     * Connections accepted so far.
     */
    public int accepted() {
        return accepted.get();
    }

    /**
     * Held connections that the client closed (for example, when its time limit ran out).
     */
    public int closedByClient() {
        return closedByClient.get();
    }

    /**
     * Whether {@code count} connections were accepted within {@code timeout}.
     */
    public boolean awaitAccepted(int count, Duration timeout) {
        return await(accepted::get, count, timeout);
    }

    /**
     * Whether the clients closed {@code count} held connections within {@code timeout}.
     */
    public boolean awaitClosedByClient(int count, Duration timeout) {
        return await(closedByClient::get, count, timeout);
    }

    /**
     * Releases the held clients and fails the next ones at once.
     */
    public void refuse() {
        refusing = true;
        held.forEach(SilentServer::closeQuietly);
        held.clear();
    }

    @Override
    public void close() {
        refuse();
        closeQuietly(server);
    }

    private void acceptAll() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                accepted.incrementAndGet();
                if (refusing) {
                    closeQuietly(socket);
                } else {
                    held.add(socket);
                    daemon("silent-server-reader-" + port(), () -> readUntilClosed(socket)).start();
                }
            } catch (IOException e) {
                return;
            }
        }
    }

    /**
     * Reads and drops what the client sends; counts the end of stream, which only the client can cause.
     */
    private void readUntilClosed(Socket socket) {
        try (InputStream in = socket.getInputStream()) {
            byte[] buffer = new byte[1024];
            while (in.read(buffer) != -1) {
                // the request is never answered
            }
            closedByClient.incrementAndGet();
        } catch (IOException e) {
            // closed by refuse() or close()
        }
    }

    private static boolean await(IntSupplier value, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (value.getAsInt() < count) {
            if (System.nanoTime() > deadline) return false;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // already closed
        }
    }
}
