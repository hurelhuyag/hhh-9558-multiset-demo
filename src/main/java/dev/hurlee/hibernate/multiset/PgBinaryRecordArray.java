package dev.hurlee.hibernate.multiset;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Decodes a PostgreSQL {@code record[]} (or any array) received in the <b>binary</b> wire format
 * into typed Java values, e.g. {@code Object[][]} for {@code array_agg(ROW(...))}.
 * <p>
 * Unlike the text literal ({@code {"(1,a)","(2,\"b, c\")"}}), the binary form of an anonymous
 * record is self-describing: {@code record_send} writes the type OID of every field, so no
 * out-of-band type information is needed. pgjdbc has no decoder for {@code record}, but it does
 * hand out the raw bytes of a binary-format column through {@link ResultSet#getBytes(int)}.
 * <p>
 * To make pgjdbc request binary format for {@code record[]} (OID 2287):
 * <pre>
 *   jdbc:postgresql://host/db?binaryTransferEnable=2287&amp;prepareThreshold=-1
 * </pre>
 * and run the query through a {@link java.sql.PreparedStatement} (binary transfer is only used
 * with server-side prepared statements; {@code prepareThreshold=-1} enables it from the first
 * execution instead of the fifth).
 * <p>
 * Wire formats (all integers big-endian):
 * <pre>
 *   array : int32 ndim, int32 hasNull, int32 elemOid, {int32 dim, int32 lowerBound} x ndim,
 *           then per element: int32 len (-1 = NULL), byte[len]
 *   record: int32 ncols, then per column: int32 typeOid, int32 len (-1 = NULL), byte[len]
 * </pre>
 */
public final class PgBinaryRecordArray {

    private static final int OID_RECORD = 2249;
    private static final int OID_RECORD_ARRAY = 2287;
    private static final long J2000_EPOCH_MICROS = 946_684_800_000_000L; // 2000-01-01T00:00Z in µs since 1970
    private static final LocalDate J2000 = LocalDate.of(2000, 1, 1);

    private PgBinaryRecordArray() {
    }

    /**
     * Reads column {@code col} of the current row as a one-dimensional {@code record[]}.
     *
     * @return one {@code Object[]} per record (a NULL array element gives a {@code null} row),
     *         an empty array for an empty SQL array, or {@code null} for SQL NULL
     */
    public static Object[][] read(ResultSet rs, int col) throws SQLException {
        byte[] raw = rs.getBytes(col);
        return raw == null ? null : decodeRecordArray(raw);
    }

    /** Like {@link #read(ResultSet, int)}, by column label; SQL NULL yields an empty array. */
    public static Object[][] read(ResultSet rs, String label) throws SQLException {
        byte[] raw = rs.getBytes(label);
        return raw == null ? new Object[0][] : decodeRecordArray(raw);
    }

    /** Decodes the binary payload of a one-dimensional {@code record[]}. */
    public static Object[][] decodeRecordArray(byte[] raw) {
        if (raw.length > 0 && raw[0] == '{') {
            throw new IllegalStateException("record[] arrived in text format; connect with "
                    + "binaryTransferEnable=2287&prepareThreshold=-1 and use a PreparedStatement");
        }
        return decodeRecordArray(ByteBuffer.wrap(raw));
    }

    /** Decodes a one-dimensional {@code record[]} body, e.g. one nested inside a {@code ROW}. */
    public static Object[][] decodeRecordArray(ByteBuffer b) {
        int ndim = b.getInt();
        b.getInt(); // hasNull flag, not needed: NULL elements are marked with len == -1
        int elemOid = b.getInt();
        if (ndim == 0) {
            return new Object[0][];
        }
        if (ndim != 1) {
            throw new IllegalArgumentException("expected a one-dimensional array, got ndim=" + ndim);
        }
        if (elemOid != OID_RECORD) {
            throw new IllegalArgumentException("expected record[] (element oid 2249), got element oid " + elemOid);
        }
        int n = b.getInt();
        b.getInt(); // lower bound
        Object[][] rows = new Object[n][];
        for (int i = 0; i < n; i++) {
            int len = b.getInt();
            rows[i] = len == -1 ? null : decodeRecord(b.slice().limit(len));
            b.position(b.position() + Math.max(len, 0));
        }
        return rows;
    }

    /** Decodes one {@code record} body: every field carries its own type OID. */
    public static Object[] decodeRecord(ByteBuffer b) {
        int ncols = b.getInt();
        Object[] fields = new Object[ncols];
        for (int i = 0; i < ncols; i++) {
            int oid = b.getInt();
            int len = b.getInt();
            if (len == -1) {
                continue; // NULL
            }
            fields[i] = decodeValue(oid, b.slice().limit(len));
            b.position(b.position() + len);
        }
        return fields;
    }

    /** Decodes any array body (elements typed by the OID in the header) into nested {@code Object[]}. */
    public static Object[] decodeArray(ByteBuffer b) {
        int ndim = b.getInt();
        b.getInt(); // hasNull
        int elemOid = b.getInt();
        if (ndim == 0) {
            return new Object[0];
        }
        int[] dims = new int[ndim];
        for (int d = 0; d < ndim; d++) {
            dims[d] = b.getInt();
            b.getInt(); // lower bound
        }
        return decodeDimension(b, elemOid, dims, 0);
    }

    private static Object[] decodeDimension(ByteBuffer b, int elemOid, int[] dims, int d) {
        Object[] out = new Object[dims[d]];
        for (int i = 0; i < dims[d]; i++) {
            if (d < dims.length - 1) {
                out[i] = decodeDimension(b, elemOid, dims, d + 1);
            } else {
                int len = b.getInt();
                if (len != -1) {
                    out[i] = decodeValue(elemOid, b.slice().limit(len));
                    b.position(b.position() + len);
                }
            }
        }
        return out;
    }

    /**
     * Decodes one non-NULL value of the given type OID. Covers the common built-in scalars;
     * anything else is returned as the raw {@code byte[]} so callers can add their own handling.
     */
    public static Object decodeValue(int oid, ByteBuffer v) {
        return switch (oid) {
            case 16 -> v.get() != 0;                                             // bool
            case 21 -> v.getShort();                                             // int2
            case 23 -> v.getInt();                                               // int4
            case 20 -> v.getLong();                                              // int8
            case 26 -> v.getInt() & 0xFFFFFFFFL;                                 // oid
            case 700 -> v.getFloat();                                            // float4
            case 701 -> v.getDouble();                                           // float8
            case 1700 -> decodeNumeric(v);                                       // numeric
            case 18, 19, 25, 705, 1042, 1043, 114 -> utf8(v);                    // char, name, text, unknown, bpchar, varchar, json
            case 3802 -> { v.get(); yield utf8(v); }                             // jsonb: version byte, then text
            case 17 -> bytes(v);                                                 // bytea
            case 2950 -> new UUID(v.getLong(), v.getLong());                     // uuid
            case 1082 -> J2000.plusDays(v.getInt());                             // date
            case 1083 -> LocalTime.ofNanoOfDay(v.getLong() * 1_000);             // time
            case 1266 -> {                                                       // timetz: µs + zone secs (west positive)
                long micros = v.getLong();
                int westSeconds = v.getInt();
                yield OffsetTime.of(LocalTime.ofNanoOfDay(micros * 1_000), ZoneOffset.ofTotalSeconds(-westSeconds));
            }
            case 1114 -> LocalDateTime.of(2000, 1, 1, 0, 0).plus(v.getLong(), ChronoUnit.MICROS); // timestamp
            case 1184 -> OffsetDateTime.ofInstant(                                                 // timestamptz
                    Instant.EPOCH.plus(J2000_EPOCH_MICROS + v.getLong(), ChronoUnit.MICROS), ZoneOffset.UTC);
            case OID_RECORD -> decodeRecord(v);                                  // nested record
            case OID_RECORD_ARRAY -> decodeRecordArray(v);                       // nested multiset
            case 1000, 1005, 1007, 1016, 1021, 1022, 1231,                       // bool[], int2[], int4[], int8[], float4[], float8[], numeric[]
                 1009, 1015, 1014, 199, 3807, 2951,                              // text[], varchar[], bpchar[], json[], jsonb[], uuid[]
                 1182, 1183, 1115, 1185 -> decodeArray(v);                       // date[], time[], timestamp[], timestamptz[]
            default -> bytes(v);
        };
    }

    /** numeric_send: int16 ndigits, weight, sign, dscale, then base-10000 digits. */
    private static Object decodeNumeric(ByteBuffer v) {
        int ndigits = v.getShort();
        int weight = v.getShort();
        int sign = v.getShort() & 0xFFFF;
        int dscale = v.getShort();
        switch (sign) {
            case 0xC000: return Double.NaN;
            case 0xD000: return Double.POSITIVE_INFINITY;
            case 0xF000: return Double.NEGATIVE_INFINITY;
            default: break;
        }
        BigInteger unscaled = BigInteger.ZERO;
        for (int i = 0; i < ndigits; i++) {
            unscaled = unscaled.multiply(BigInteger.valueOf(10_000)).add(BigInteger.valueOf(v.getShort()));
        }
        if (sign == 0x4000) {
            unscaled = unscaled.negate();
        }
        // digits after the decimal point = (ndigits - weight - 1) groups of 4
        int scale = ndigits == 0 ? 0 : (ndigits - weight - 1) * 4;
        return new BigDecimal(unscaled, scale).setScale(dscale);
    }

    private static String utf8(ByteBuffer v) {
        return StandardCharsets.UTF_8.decode(v).toString();
    }

    private static byte[] bytes(ByteBuffer v) {
        byte[] out = new byte[v.remaining()];
        v.get(out);
        return out;
    }
}
