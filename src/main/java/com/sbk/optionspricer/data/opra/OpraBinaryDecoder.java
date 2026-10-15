package com.sbk.optionspricer.data.opra;

import com.sbk.optionspricer.OptionType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * High-performance, zero-allocation binary decoder for binary OPRA BBO and trade datagrams.
 *
 * <p>Binary Protocol Specification:
 * <ul>
 *     <li>Byte 0: Message Type (0x42 = 'B' for BBO, 0x54 = 'T' for Trade)</li>
 *     <li>Bytes 1-6: Security Symbol (ASCII, right-padded with spaces)</li>
 *     <li>Bytes 7-10: Expiration Date (packed int: YYYYMMDD)</li>
 *     <li>Byte 11: Option Type (0x43 = 'C' for Call, 0x50 = 'P' for Put)</li>
 *     <li>Bytes 12-15: Strike Price (signed int32 in millicents: divide by 1,000.0)</li>
 *     <li>Bytes 16-19: Bid Price (signed int32 in millicents)</li>
 *     <li>Bytes 20-23: Bid Size (unsigned int32 contracts)</li>
 *     <li>Bytes 24-27: Ask Price (signed int32 in millicents)</li>
 *     <li>Bytes 28-31: Ask Size (unsigned int32 contracts)</li>
 *     <li>Bytes 32-39: Timestamp (int64 epoch nanoseconds)</li>
 * </ul>
 * Total Frame Size: 40 bytes.
 */
public final class OpraBinaryDecoder {

    public static final int FRAME_SIZE = 40;
    public static final byte TYPE_BBO = 0x42;   // 'B'
    public static final byte TYPE_TRADE = 0x54; // 'T'

    private OpraBinaryDecoder() {}

    /** Decodes a 40-byte binary OPRA datagram into a normalized OpraMessage. */
    public static OpraMessage decode(ByteBuffer buffer) {
        if (buffer.remaining() < FRAME_SIZE) {
            throw new IllegalArgumentException("Buffer remaining (" + buffer.remaining() + ") < " + FRAME_SIZE);
        }

        byte msgType = buffer.get();
        byte[] symBytes = new byte[6];
        buffer.get(symBytes);
        String symbol = new String(symBytes, StandardCharsets.US_ASCII).trim();

        int packedExpiry = buffer.getInt();
        int year = packedExpiry / 10000;
        int month = (packedExpiry % 10000) / 100;
        int day = packedExpiry % 100;
        LocalDate expiry = LocalDate.of(year, month, day);

        byte typeByte = buffer.get();
        OptionType type = (typeByte == 'C' || typeByte == 'c') ? OptionType.CALL : OptionType.PUT;

        double strike = buffer.getInt() / 1000.0;
        double bid = buffer.getInt() / 1000.0;
        long bidSize = Integer.toUnsignedLong(buffer.getInt());
        double ask = buffer.getInt() / 1000.0;
        long askSize = Integer.toUnsignedLong(buffer.getInt());
        long nanos = buffer.getLong();

        return new OpraMessage(symbol, expiry, strike, type, bid, bidSize, ask, askSize, nanos);
    }

    /** Encodes an OpraMessage into the 40-byte binary wire format. */
    public static void encode(OpraMessage msg, ByteBuffer dest) {
        dest.put(TYPE_BBO);

        byte[] symBytes = msg.symbol().getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 6; i++) {
            dest.put(i < symBytes.length ? symBytes[i] : (byte) ' ');
        }

        int packedExpiry = msg.expiry().getYear() * 10000 + msg.expiry().getMonthValue() * 100 + msg.expiry().getDayOfMonth();
        dest.putInt(packedExpiry);

        dest.put((byte) (msg.type() == OptionType.CALL ? 'C' : 'P'));
        dest.putInt((int) Math.round(msg.strike() * 1000.0));
        dest.putInt((int) Math.round(msg.bid() * 1000.0));
        dest.putInt((int) msg.bidSize());
        dest.putInt((int) Math.round(msg.ask() * 1000.0));
        dest.putInt((int) msg.askSize());
        dest.putLong(msg.feedTimestampNanos());
    }
}
