package com.shrieeiee.blinktv;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * One Blink live view over Blink's "IMMIS" relay protocol (port of blinkpy's livestream.py).
 *
 * Blink relay ──TLS, 9-byte framed packets──► this ──plain MPEG-TS over HTTP on 127.0.0.1──► ExoPlayer
 *
 * The local HTTP hop means the player is a stock progressive TS player; nothing leaves the TV.
 */
final class ImmisStream {

    private static final int MAX_PAYLOAD = 4 * 1024 * 1024;

    /** What the stream needs from the outside world (Blink API calls, logging). */
    interface Hooks {
        /** @return false once Blink says the live-view command has finished. Throw to mean "don't know". */
        boolean commandRunning() throws Exception;
        void commandDone();
        void log(String msg);
    }

    private final Hooks hooks;
    private final String serial;
    private final long commandId;
    private final int pollSeconds;
    private final String host;
    private final int port;
    private final int clientId;
    private final String connId;

    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(6000);
    private final Object writeLock = new Object();
    private volatile boolean stopped = false;
    private volatile boolean upstreamEnded = false;
    private SSLSocket relay;
    private OutputStream relayOut;
    private ServerSocket local;
    private Socket player;

    ImmisStream(String server, String serial, long commandId, int pollSeconds, Hooks hooks) throws IOException {
        this.hooks = hooks;
        this.serial = serial;
        this.commandId = commandId;
        this.pollSeconds = Math.max(2, pollSeconds);
        // immis://1.2.3.4:443/ABCDEFGHIJKML__IMDS_1234567812345678?client_id=123456
        URI uri;
        try {
            uri = new URI(server);
        } catch (Exception e) {
            throw new IOException("Blink sent a live-view address the app doesn't understand.");
        }
        host = uri.getHost();
        port = uri.getPort() > 0 ? uri.getPort() : 443;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        connId = last.contains("__") ? last.substring(0, last.indexOf("__")) : last;
        int cid = 0;
        String query = uri.getRawQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                if (kv.startsWith("client_id=")) {
                    try { cid = Integer.parseInt(kv.substring(10)); } catch (NumberFormatException ignored) { }
                }
            }
        }
        clientId = cid;
        if (host == null) throw new IOException("Blink's live-view address had no host.");
    }

    /** Connects to Blink and returns the local URL to hand to the player. */
    String start() throws IOException {
        SSLContext tls;
        try {
            tls = SSLContext.getInstance("TLS");
            // Blink's relay presents a certificate for an internal name on a bare IP; blinkpy
            // doesn't verify it either. The stream itself is authenticated by the connection id.
            tls.init(null, new TrustManager[]{new TrustAll()}, new SecureRandom());
        } catch (Exception e) {
            throw new IOException("TLS unavailable: " + e.getMessage());
        }
        relay = (SSLSocket) tls.getSocketFactory().createSocket();
        relay.connect(new InetSocketAddress(host, port), 15000);
        relay.setSoTimeout(30000);
        relay.startHandshake();
        relayOut = relay.getOutputStream();
        synchronized (writeLock) {
            relayOut.write(authHeader());
            relayOut.flush();
        }

        local = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));

        thread("immis-recv", this::receiveLoop);
        thread("immis-keepalive", this::keepAliveLoop);
        thread("immis-poll", this::pollLoop);
        thread("immis-serve", this::serveLoop);
        return "http://127.0.0.1:" + local.getLocalPort() + "/live.ts";
    }

    /** Tears everything down and tells Blink we're done (in the background). */
    void stop() {
        stop(false);
    }

    /**
     * @param waitForBlink true = tell Blink "done" on this thread before returning, so the
     *                     camera's single live slot is free before we ask for another one.
     */
    void stop(boolean waitForBlink) {
        synchronized (this) {
            if (stopped) return;
            stopped = true;
        }
        closeQuietly(relay);
        closeQuietly(player);
        try { if (local != null) local.close(); } catch (IOException ignored) { }
        queue.clear();
        if (commandId == 0) return;
        if (waitForBlink) hooks.commandDone(); else thread("immis-done", hooks::commandDone);
    }

    // ------------------------------------------------------------------ protocol

    /** 122-byte hello: magic, serial, client id, static, empty token, connection id, trailer. */
    byte[] authHeader() {
        ByteArrayOutputStream b = new ByteArrayOutputStream(122);
        writeInt(b, 0x28);
        stringField(b, serial, 16);
        writeInt(b, clientId);
        b.write(0x01);
        b.write(0x08);
        writeInt(b, 64);
        b.write(new byte[64], 0, 64);
        stringField(b, connId, 16);
        writeInt(b, 1);
        return b.toByteArray();
    }

    private static void stringField(ByteArrayOutputStream b, String s, int max) {
        byte[] raw = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[max];
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, max));
        writeInt(b, max);
        b.write(padded, 0, max);
    }

    private static void writeInt(ByteArrayOutputStream b, int v) {
        b.write((v >>> 24) & 0xFF);
        b.write((v >>> 16) & 0xFF);
        b.write((v >>> 8) & 0xFF);
        b.write(v & 0xFF);
    }

    private void receiveLoop() {
        try {
            DataInputStream in = new DataInputStream(relay.getInputStream());
            byte[] header = new byte[9];
            while (!stopped) {
                in.readFully(header);
                int type = header[0] & 0xFF;
                int length = ((header[5] & 0xFF) << 24) | ((header[6] & 0xFF) << 16)
                        | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF);
                if (length <= 0) continue;
                if (length > MAX_PAYLOAD) throw new IOException("Silly packet length " + length);
                byte[] payload = new byte[length];
                in.readFully(payload);
                // Type 0 = the MPEG-TS video/audio stream. Everything else is control chatter.
                if (type != 0x00 || payload[0] != 0x47) continue;
                if (!queue.offer(payload)) {
                    // Player fell behind (or hasn't connected yet): drop the oldest, keep it live.
                    queue.poll();
                    queue.offer(payload);
                }
            }
        } catch (IOException e) {
            if (!stopped) hooks.log("relay ended: " + e);
        } finally {
            upstreamEnded = true;
        }
    }

    private void keepAliveLoop() {
        byte[] latency = new byte[]{
                0x12, 0x00, 0x00, 0x03, (byte) 0xE8, 0x00, 0x00, 0x00, 0x18,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        int tick = 0;
        int seq = 0;
        try {
            while (!stopped && !upstreamEnded) {
                synchronized (writeLock) {
                    if (tick % 10 == 0) {
                        seq++;
                        relayOut.write(new byte[]{0x0A,
                                (byte) (seq >>> 24), (byte) (seq >>> 16), (byte) (seq >>> 8), (byte) seq,
                                0, 0, 0, 0});
                    }
                    relayOut.write(latency);
                    relayOut.flush();
                }
                tick++;
                Thread.sleep(1000);
            }
        } catch (IOException | InterruptedException e) {
            if (!stopped) hooks.log("keepalive ended: " + e);
        }
    }

    private void pollLoop() {
        if (commandId == 0) return;
        try {
            while (!stopped && !upstreamEnded) {
                Thread.sleep(pollSeconds * 1000L);
                if (stopped) return;
                boolean running;
                try {
                    running = hooks.commandRunning();
                } catch (Exception e) {
                    continue; // a blip in the cloud isn't a reason to kill a working stream
                }
                if (!running) {
                    hooks.log("Blink closed the live-view command");
                    closeQuietly(relay); // receive loop ends, player gets EOF, app reconnects
                    return;
                }
            }
        } catch (InterruptedException ignored) { }
    }

    /** Minimal HTTP server: one GET, then raw TS until the relay ends. */
    private void serveLoop() {
        while (!stopped) {
            Socket s;
            try {
                s = local.accept();
            } catch (IOException e) {
                return;
            }
            closeQuietly(player);
            player = s;
            try {
                s.setTcpNoDelay(true);
                readRequestHeaders(s.getInputStream());
                OutputStream out = s.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: video/mp2t\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                while (!stopped) {
                    byte[] chunk = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        out.write(chunk);
                        continue;
                    }
                    out.flush();
                    if (upstreamEnded && queue.isEmpty()) break;
                }
                out.flush();
            } catch (IOException | InterruptedException e) {
                // Player went away; it may reconnect.
            } finally {
                closeQuietly(s);
            }
            if (upstreamEnded) return;
        }
    }

    private static void readRequestHeaders(InputStream in) throws IOException {
        // Read up to the blank line ending the request; we don't care what it says.
        int state = 0;
        int count = 0;
        while (state < 4 && count++ < 16384) {
            int c = in.read();
            if (c < 0) return;
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') state++;
            else state = c == '\r' ? 1 : 0;
        }
    }

    // ------------------------------------------------------------------ bits

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }

    private static final class TrustAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
