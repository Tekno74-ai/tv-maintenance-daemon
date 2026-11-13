package io.github.tekno74.tvmaintenance.adb;

import android.content.Context;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Module 2c (adb): Lightweight, zero-dependency ADB TCP wire protocol client for 127.0.0.1:5555.
 * Connects strictly to loopback (127.0.0.1) to execute privileged shell maintenance commands.
 */
public final class LocalAdbClient {
    private static final String TAG = "TvMaintAdbClient";
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int ADB_VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;

    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;

    private final Context context;
    private final int port;

    public LocalAdbClient(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    /**
     * Executes a shell command over 127.0.0.1:<port> and returns stdout.
     */
    public String runShellCommand(String command, int timeoutMs) throws Exception {
        AdbCrypto crypto = AdbCrypto.loadOrCreate(context);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK_HOST, port), Math.min(timeoutMs, 5000));
            socket.setSoTimeout(timeoutMs);

            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            // 1. Send A_CNXN
            sendPacket(out, A_CNXN, ADB_VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));

            Packet pkt = readPacket(in);
            if (pkt.command == A_AUTH && pkt.arg0 == ADB_AUTH_TOKEN) {
                // 2. Sign token with our private key
                byte[] sig = crypto.signToken(pkt.data);
                sendPacket(out, A_AUTH, ADB_AUTH_SIGNATURE, 0, sig);
                pkt = readPacket(in);

                if (pkt.command == A_AUTH && pkt.arg0 == ADB_AUTH_TOKEN) {
                    // Key not yet in /data/misc/adb/adb_keys -> send public key for one-time user prompt
                    Log.i(TAG, "Sending RSA public key to adbd for one-time authorization...");
                    sendPacket(out, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, crypto.getAdbPublicKeyPayload());
                    pkt = readPacket(in);
                }
            }

            if (pkt.command != A_CNXN) {
                throw new IOException("Unexpected ADB handshake response: 0x" + Integer.toHexString(pkt.command));
            }

            // 3. Open shell stream (localId = 1)
            int localId = 1;
            byte[] openData = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
            sendPacket(out, A_OPEN, localId, 0, openData);

            StringBuilder output = new StringBuilder();
            while (true) {
                Packet msg = readPacket(in);
                if (msg.command == A_OKAY) {
                    continue;
                } else if (msg.command == A_WRTE) {
                    int remoteId = msg.arg0;
                    if (msg.data != null && msg.data.length > 0) {
                        output.append(new String(msg.data, StandardCharsets.UTF_8));
                    }
                    sendPacket(out, A_OKAY, localId, remoteId, null);
                } else if (msg.command == A_CLSE) {
                    int remoteId = msg.arg0;
                    sendPacket(out, A_CLSE, localId, remoteId, null);
                    break;
                } else {
                    break;
                }
            }
            return output.toString().trim();
        }
    }

    private static void sendPacket(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        int len = (data == null) ? 0 : data.length;
        int checksum = 0;
        if (data != null) {
            for (byte b : data) {
                checksum += (b & 0xFF);
            }
        }
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command);
        header.putInt(arg0);
        header.putInt(arg1);
        header.putInt(len);
        header.putInt(checksum);
        header.putInt(command ^ 0xFFFFFFFF);
        out.write(header.array());
        if (data != null && len > 0) {
            out.write(data);
        }
        out.flush();
    }

    private static Packet readPacket(DataInputStream in) throws IOException {
        byte[] hdrBytes = new byte[24];
        in.readFully(hdrBytes);
        ByteBuffer hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN);
        int command = hdr.getInt();
        int arg0 = hdr.getInt();
        int arg1 = hdr.getInt();
        int len = hdr.getInt();
        hdr.getInt(); // checksum
        hdr.getInt(); // magic

        if (len < 0 || len > 1024 * 1024) {
            throw new IOException("Invalid ADB packet length: " + len);
        }
        byte[] data = new byte[len];
        if (len > 0) {
            in.readFully(data);
        }
        return new Packet(command, arg0, arg1, data);
    }

    private static final class Packet {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] data;

        Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
