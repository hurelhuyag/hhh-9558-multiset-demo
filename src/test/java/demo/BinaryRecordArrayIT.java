package demo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code array_agg(ROW(...))} received in binary format and decoded into typed {@code Object[][]}
 * without any out-of-band type information — the field types travel on the wire.
 */
class BinaryRecordArrayIT {

    private static Connection c;

    @BeforeAll
    static void setUp() throws Exception {
        TestDb.connect().close();
        c = DriverManager.getConnection(TestDb.BINARY_URL, TestDb.USER, TestDb.PASSWORD);
        try (Statement st = c.createStatement()) {
            st.execute("drop table if exists bin_tag, bin_animal");
            st.execute("create table bin_animal(ani_id int primary key, ani_name text)");
            st.execute("create table bin_tag(tag_id int primary key, ani_id int, tag text)");
            st.execute("insert into bin_animal values (1,'cat'),(2,'dog'),(3,'fish')");
            st.execute("insert into bin_tag values (100,1,'fluffy'),(101,1,'says \"meow\"'),(102,1,null),(103,2,'good boy')");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        c.close();
    }

    @Test
    void multiset_oneRowPerParent_typedFields() throws Exception {
        String sql = """
            select a.ani_id,
                   (select array_agg(row(t.tag_id, t.tag) order by t.tag_id) from bin_tag t where t.ani_id = a.ani_id) as tags
              from bin_animal a order by a.ani_id
            """;
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(1, rs.getInt(1));
            Object[][] tags = PgBinaryRecordArray.read(rs, 2);
            assertArrayEquals(new Object[][] { { 100, "fluffy" }, { 101, "says \"meow\"" }, { 102, null } }, tags);
            assertEquals(Integer.class, tags[0][0].getClass()); // typed, not a String

            rs.next();
            assertArrayEquals(new Object[][] { { 103, "good boy" } }, PgBinaryRecordArray.read(rs, 2));

            rs.next();
            assertNull(PgBinaryRecordArray.read(rs, 2)); // array_agg over no rows -> SQL NULL

            assertEquals(false, rs.next());
        }
    }

    @Test
    void allCommonScalarTypes_andNulls() throws Exception {
        String sql = """
            select array_agg(r) from (values
              (row(true, 7::int2, 42, 10000000000::int8, 1.5::float4, 2.25::float8, 12345.6789::numeric, -0.05::numeric, 3::numeric,
                   'txt', 'vc'::varchar, 'ab'::char(4), '\\xdeadbeef'::bytea, 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid,
                   date '2026-09-16', time '10:11:12.5', timestamp '2026-09-16 10:11:12', timestamptz '2026-09-16 10:11:12+02',
                   '{"k":1}'::json, '{"k":2}'::jsonb)),
              (row(null, null, null, null, null, null, null, null, null,
                   null, null, null, null, null,
                   null, null, null, null,
                   null, null))
            ) v(r)
            """;
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            Object[][] rows = PgBinaryRecordArray.read(rs, 1);
            assertEquals(2, rows.length);
            Object[] r = rows[0];
            assertEquals(true, r[0]);
            assertEquals((short) 7, r[1]);
            assertEquals(42, r[2]);
            assertEquals(10_000_000_000L, r[3]);
            assertEquals(1.5f, r[4]);
            assertEquals(2.25d, r[5]);
            assertEquals(new BigDecimal("12345.6789"), r[6]);
            assertEquals(new BigDecimal("-0.05"), r[7]);
            assertEquals(new BigDecimal("3"), r[8]);
            assertEquals("txt", r[9]);
            assertEquals("vc", r[10]);
            assertEquals("ab  ", r[11]);
            assertArrayEquals(new byte[] { (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef }, (byte[]) r[12]);
            assertEquals(UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"), r[13]);
            assertEquals(LocalDate.of(2026, 9, 16), r[14]);
            assertEquals(LocalTime.of(10, 11, 12, 500_000_000), r[15]);
            assertEquals(LocalDateTime.of(2026, 9, 16, 10, 11, 12), r[16]);
            assertEquals(OffsetDateTime.of(2026, 9, 16, 8, 11, 12, 0, ZoneOffset.UTC), r[17]);
            assertEquals("{\"k\":1}", r[18]);
            assertEquals("{\"k\": 2}", r[19]);

            assertArrayEquals(new Object[20], rows[1]); // every field NULL
        }
    }

    @Test
    void nestedArraysAndRecords_nullElement_emptyArray() throws Exception {
        String sql = """
            select array_agg(r) as nested,
                   (select array_agg(row(1)) where false) as sql_null,
                   coalesce((select array_agg(row(1)) where false), '{}') as empty
              from (values (row(array[1,2,3], row('in', 9))), (null::record)) v(r)
            """;
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            Object[][] nested = PgBinaryRecordArray.read(rs, 1);
            assertEquals(2, nested.length);
            assertArrayEquals(new Object[] { 1, 2, 3 }, (Object[]) nested[0][0]);
            assertArrayEquals(new Object[] { "in", 9 }, (Object[]) nested[0][1]);
            assertNull(nested[1]); // NULL array element -> null row

            assertNull(PgBinaryRecordArray.read(rs, 2));
            assertEquals(0, PgBinaryRecordArray.read(rs, 3).length);
        }
    }

    @Test
    void textFormat_isRejectedWithHint() throws Exception {
        // a connection without binaryTransferEnable receives the text literal
        try (Connection plain = TestDb.connect();
             Statement st = plain.createStatement();
             ResultSet rs = st.executeQuery("select array_agg(row(1,'a'))")) {
            rs.next();
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> PgBinaryRecordArray.read(rs, 1));
            assertEquals(true, e.getMessage().contains("binaryTransferEnable"));
        }
    }
}
