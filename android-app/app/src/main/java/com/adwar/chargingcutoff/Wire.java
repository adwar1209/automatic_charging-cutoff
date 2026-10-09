package com.adwar.chargingcutoff;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** No Android dependencies: the exact wire format is also tested on a host JVM. */
public final class Wire {
    public static final UUID SERVICE = UUID.fromString("7d7e0001-8c6e-4d26-b11c-63430d8b23cc");
    public static final UUID COMMAND = UUID.fromString("7d7e0002-8c6e-4d26-b11c-63430d8b23cc");
    public static final UUID STATUS = UUID.fromString("7d7e0003-8c6e-4d26-b11c-63430d8b23cc");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final int START = 1, REPORT = 2, STOP = 3;
    public static final int READY = 0, CHARGING = 1, ENDED = 2, FAULT = 3;
    public static final long HEARTBEAT_MS = 30_000L;
    private Wire() {}

    public static byte[] command(int operation, int percent, long nonce, long session, long sequence) {
        if (operation < START || operation > STOP || nonce <= 0 || nonce > 0xffffffffL ||
            session <= 0 || session > 0xffffffffL || sequence <= 0 || sequence > 0xffffffffL ||
            (operation == START && sequence != 1) ||
            (operation == STOP ? percent != 255 : percent < 0 || percent > 100))
            throw new IllegalArgumentException("Invalid command values");
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte)1).put((byte)operation).put((byte)percent).put((byte)0)
            .putInt((int)nonce).putInt((int)session).putInt((int)sequence).array();
    }

    public static Status decode(byte[] data) {
        if (data == null || data.length != 20) throw new IllegalArgumentException("Status must be 20 bytes");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int version = b.get() & 255, state = b.get() & 255, reason = b.get() & 255, relay = b.get() & 255;
        long nonce = Integer.toUnsignedLong(b.getInt());
        long session = Integer.toUnsignedLong(b.getInt());
        long sequence = Integer.toUnsignedLong(b.getInt());
        int percent = b.get() & 255;
        if (version != 1 || state > FAULT || reason > 8 || relay > 1 ||
            (percent > 100 && percent != 255) || b.get() != 0 || b.get() != 0 || b.get() != 0 ||
            (state != CHARGING && relay != 0) ||
            (state == CHARGING && (relay != 1 || percent >= 100 || session == 0 || sequence == 0 || reason != 0)))
            throw new IllegalArgumentException("Invalid or inconsistent controller status");
        return new Status(state, reason, relay == 1, nonce, session, sequence, percent);
    }

    public static String reasonText(int reason) {
        switch (reason) {
            case 0: return "Ready";
            case 1: return "Charge limit reached";
            case 2: return "Stopped by you";
            case 3: return "Bluetooth disconnected";
            case 4: return "Battery reporting timed out";
            case 5: return "Invalid battery message";
            case 6: return "Session mismatch";
            case 7: return "Old or repeated message rejected";
            case 8: return "Controller reset";
            default: return "Unknown controller response";
        }
    }

    public static final class Status {
        public final int state, reason, percent;
        public final boolean relayOn;
        public final long nonce, session, sequence;
        public Status(int state, int reason, boolean relayOn, long nonce, long session, long sequence, int percent) {
            this.state=state; this.reason=reason; this.relayOn=relayOn;
            this.nonce=nonce; this.session=session; this.sequence=sequence; this.percent=percent;
        }
    }
}
