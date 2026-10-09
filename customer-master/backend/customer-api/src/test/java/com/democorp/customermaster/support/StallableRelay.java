package com.democorp.customermaster.support;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A loopback TCP relay to the shared database that can stall: while stalled it keeps every socket
 * open but forwards no byte in either direction, as a paused or partitioned database server
 * behaves. Bytes read while stalled are held and forwarded on release, so connections that
 * outlive the stall carry on. Every thread is a daemon, and {@link #close()} closes every socket.
 *
 * <p>Tests point a short-lived pool at {@link #port()} on the loopback address, stall the relay
 * after a successful round trip, and release it in {@code finally}, so a blocked thread never
 * outlives the test. The shared container never notices the stall.
 *
 * <p>Example:
 * <pre>{@code
 * try (StallableRelay relay = new StallableRelay(new InetSocketAddress(
 *         POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)))) {
 *     // start a pool on relay.port(), use it once, then:
 *     relay.stall();
 *     // ... assert the bounded failure ...
 *     relay.release();
 * }
 * }</pre>
 *
 * <p>This class carries no Spring stereotype: test classes are on the classpath when
 * {@code GeneratorProcessIT}'s {@code ApplicationContextRunner} runs the application's component scan.
 */
public final class StallableRelay implements AutoCloseable {

    /** Longest wait for the relay's own connection to the database. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Size of the buffer of each relay direction. */
    private static final int BUFFER_SIZE = 8_192;

    /** The listening loopback socket the pool connects to. */
    private final ServerSocket server;

    /** The database address. */
    private final InetSocketAddress target;

    /** Every open client and database socket, closed by {@link #close()}. */
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

    /** Guards {@link #stalled}; forwarding threads wait on it while stalled. */
    private final Object gate = new Object();

    /** Whether forwarding is suspended; guarded by {@link #gate}. */
    private boolean stalled;

    /** Set once by {@link #close()}. */
    private volatile boolean closed;

    /**
     * Starts the relay on an ephemeral loopback port.
     *
     * @param target the database address
     * @throws IOException if no loopback port can be bound
     */
    public StallableRelay(InetSocketAddress target) throws IOException {
        this.target = target;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        daemon("relay-accept", this::acceptConnections).start();
    }

    /**
     * Returns the port the pool connects to.
     *
     * @return the listening loopback port
     */
    public int port() {
        return server.getLocalPort();
    }

    /** Stops forwarding in both directions; every socket stays open. */
    public void stall() {
        synchronized (gate) {
            stalled = true;
        }
    }

    /** Resumes forwarding, starting with the bytes held during the stall. */
    public void release() {
        synchronized (gate) {
            stalled = false;
            gate.notifyAll();
        }
    }

    /**
     * Accepts connections until the relay closes, pairing each with a new database connection
     * and one forwarding thread per direction.
     */
    private void acceptConnections() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                // The server socket was closed by close(): no further connection is accepted.
                return;
            }
            Socket database = new Socket();
            sockets.add(client);
            sockets.add(database);
            try {
                database.connect(target, CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                // The database refused or timed out: the client sees its connection closed.
                closeQuietly(client);
                closeQuietly(database);
                continue;
            }
            if (closed) {
                closeQuietly(client);
                closeQuietly(database);
                return;
            }
            daemon("relay-to-database", () -> forward(client, database)).start();
            daemon("relay-to-client", () -> forward(database, client)).start();
        }
    }

    /**
     * Copies bytes from one socket to the other, holding them while the relay is stalled, until
     * either side closes; then closes both.
     *
     * @param from the socket read
     * @param to   the socket written
     */
    private void forward(Socket from, Socket to) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int count;
            while ((count = in.read(buffer)) != -1) {
                awaitForwarding();
                out.write(buffer, 0, count);
                out.flush();
            }
        } catch (IOException e) {
            // One side closed or reset the connection: this direction ends, and finally closes both.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    /**
     * Waits while the relay is stalled and open.
     *
     * @throws InterruptedException if the forwarding thread is interrupted
     */
    private void awaitForwarding() throws InterruptedException {
        synchronized (gate) {
            while (stalled && !closed) {
                gate.wait();
            }
        }
    }

    /** Stops accepting, releases waiting threads and closes every socket. */
    @Override
    public void close() {
        closed = true;
        release();
        closeQuietly(server);
        sockets.forEach(StallableRelay::closeQuietly);
    }

    /**
     * Creates a daemon thread, so a relay thread left blocked by a failed test never keeps the JVM
     * alive.
     *
     * @param name the thread name
     * @param task the work of the thread
     * @return the unstarted thread
     */
    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Closes a socket that may already be closed or reset.
     *
     * @param closeable the socket
     */
    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            // Already closed or reset by the peer: nothing is left to release.
        }
    }
}
