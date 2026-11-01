package demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Experiment: H2 can return a nested collection as an ARRAY of ROW values ("arrays of rows").
 * <ol>
 *   <li>Probe: what does the H2 JDBC driver hand over for {@code array_agg(row(...))}?</li>
 *   <li>Benchmark (H2 in-memory): arrays of rows vs. {@code json_arrayagg(json_array(...))} + JSON parsing
 *       vs. a plain join, all decoded into the same typed Java records.</li>
 * </ol>
 * Run: {@code java -cp <test classpath> demo.H2ArrayOfRowsExample}
 */
public class H2ArrayOfRowsExample {

    record Line(long id, String product, int qty, BigDecimal price, LocalDateTime created, boolean active, LocalDate shipped) { }
    record Order(long id, String customer, List<Line> lines) { }

    static final int ORDERS = 1000;
    static final int LINES = 8;

    static final String COLUMNS = "l.id, l.product, l.qty, l.price, l.created, l.active, l.shipped";

    static final String ROWS_SQL = "select o.id, o.customer, "
            + "(select array_agg(row(" + COLUMNS + ") order by l.id) from line l where l.order_id = o.id) "
            + "from orders o where o.id <= ? order by o.id";

    static final String JSON_SQL = "select o.id, o.customer, "
            + "(select json_arrayagg(json_array(" + COLUMNS + " null on null) order by l.id) from line l where l.order_id = o.id) "
            + "from orders o where o.id <= ? order by o.id";

    static final String JOIN_SQL = "select o.id, o.customer, " + COLUMNS
            + " from orders o left join line l on l.order_id = o.id where o.id <= ? order by o.id, l.id";

    static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:rows;DB_CLOSE_DELAY=-1")) {
            createData(c);
            probe(c);
            verifySameResults(c);
            System.out.println();
            System.out.printf("%-12s %-12s %10s %10s%n", "owners", "method", "median ms", "p90 ms");
            for (int owners : new int[] { 1, 50, 1000 }) {
                int warmup = owners == 1000 ? 30 : 300;
                int runs = owners == 1000 ? 100 : 1000;
                bench(c, owners, "join", JOIN_SQL, H2ArrayOfRowsExample::readJoin, warmup, runs);
                bench(c, owners, "json", JSON_SQL, H2ArrayOfRowsExample::readJson, warmup, runs);
                bench(c, owners, "array/rows", ROWS_SQL, H2ArrayOfRowsExample::readRows, warmup, runs);
            }
        }
    }

    // ~~~ probe

    static void probe(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ROWS_SQL)) {
            ps.setLong(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Object value = rs.getObject(3);
                System.out.println("column value class : " + value.getClass().getName());
                Object[] elements = (Object[]) ((Array) value).getArray();
                Object first = elements[0];
                System.out.println("element class      : " + first.getClass().getName());
                Object[] fields = fields(first);
                for (int i = 0; i < fields.length; i++) {
                    System.out.println("  field " + (i + 1) + " : " + (fields[i] == null ? "null" : fields[i].getClass().getName() + " = " + fields[i]));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(JSON_SQL)) {
            ps.setLong(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String json = rs.getString(3);
                System.out.println("json (first 160)   : " + json.substring(0, Math.min(160, json.length())));
            }
        }
    }

    /** The fields of one ROW value, whatever the driver represents it as. */
    static Object[] fields(Object row) throws SQLException {
        if (row instanceof Object[] array) {
            return array;
        }
        if (row instanceof ResultSet rs) {
            int n = rs.getMetaData().getColumnCount();
            rs.beforeFirst();
            rs.next();
            Object[] fields = new Object[n];
            for (int i = 0; i < n; i++) {
                fields[i] = rs.getObject(i + 1);
            }
            return fields;
        }
        throw new IllegalStateException("unexpected ROW representation: " + row.getClass());
    }

    // ~~~ readers, all producing the same typed records

    interface Reader {
        List<Order> read(ResultSet rs) throws Exception;
    }

    static List<Order> readRows(ResultSet rs) throws Exception {
        List<Order> orders = new ArrayList<>();
        while (rs.next()) {
            List<Line> lines = new ArrayList<>();
            Array array = rs.getArray(3);
            if (array != null) {
                for (Object row : (Object[]) array.getArray()) {
                    Object[] f = fields(row);
                    lines.add(new Line(((Number) f[0]).longValue(), (String) f[1], ((Number) f[2]).intValue(),
                            (BigDecimal) f[3], localDateTime(f[4]), (Boolean) f[5], localDate(f[6])));
                }
            }
            orders.add(new Order(rs.getLong(1), rs.getString(2), lines));
        }
        return orders;
    }

    static List<Order> readJson(ResultSet rs) throws Exception {
        List<Order> orders = new ArrayList<>();
        while (rs.next()) {
            List<Line> lines = new ArrayList<>();
            String json = rs.getString(3);
            if (json != null) {
                for (JsonNode row : JSON.readTree(json)) {
                    lines.add(new Line(row.get(0).longValue(), row.get(1).textValue(), row.get(2).intValue(),
                            new BigDecimal(row.get(3).asText()),
                            LocalDateTime.parse(row.get(4).textValue().replace(' ', 'T')),
                            row.get(5).booleanValue(), LocalDate.parse(row.get(6).textValue())));
                }
            }
            orders.add(new Order(rs.getLong(1), rs.getString(2), lines));
        }
        return orders;
    }

    static List<Order> readJoin(ResultSet rs) throws Exception {
        List<Order> orders = new ArrayList<>();
        Order current = null;
        while (rs.next()) {
            long id = rs.getLong(1);
            if (current == null || current.id() != id) {
                current = new Order(id, rs.getString(2), new ArrayList<>());
                orders.add(current);
            }
            if (rs.getObject(3) != null) {
                current.lines().add(new Line(rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getBigDecimal(6),
                        rs.getTimestamp(7).toLocalDateTime(), rs.getBoolean(8), rs.getDate(9).toLocalDate()));
            }
        }
        return orders;
    }

    static LocalDateTime localDateTime(Object value) {
        return value instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
    }

    static LocalDate localDate(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
    }

    // ~~~ checks and timing

    static void verifySameResults(Connection c) throws Exception {
        List<Order> join = query(c, JOIN_SQL, H2ArrayOfRowsExample::readJoin, ORDERS);
        List<Order> json = query(c, JSON_SQL, H2ArrayOfRowsExample::readJson, ORDERS);
        List<Order> rows = query(c, ROWS_SQL, H2ArrayOfRowsExample::readRows, ORDERS);
        if (!join.equals(json) || !join.equals(rows)) {
            throw new IllegalStateException("the three methods return different data");
        }
        System.out.println("same data from all three methods: " + join.size() + " orders, "
                + join.stream().mapToInt(o -> o.lines().size()).sum() + " lines");
    }

    static List<Order> query(Connection c, String sql, Reader reader, int owners) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, owners);
            try (ResultSet rs = ps.executeQuery()) {
                return reader.read(rs);
            }
        }
    }

    static void bench(Connection c, int owners, String name, String sql, Reader reader, int warmup, int runs) throws Exception {
        long[] nanos = new long[runs];
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, owners);
            for (int i = -warmup; i < runs; i++) {
                long start = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) {
                    reader.read(rs);
                }
                if (i >= 0) {
                    nanos[i] = System.nanoTime() - start;
                }
            }
        }
        Arrays.sort(nanos);
        System.out.printf("%-12d %-12s %10.3f %10.3f%n", owners, name, nanos[runs / 2] / 1e6, nanos[runs * 9 / 10] / 1e6);
    }

    static void createData(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("create table orders (id bigint primary key, customer varchar(100))");
            s.execute("create table line (id bigint primary key, order_id bigint, product varchar(100), qty int, "
                    + "price numeric(10,2), created timestamp, active boolean, shipped date)");
            s.execute("create index line_order on line(order_id)");
        }
        try (PreparedStatement o = c.prepareStatement("insert into orders values (?, ?)");
             PreparedStatement l = c.prepareStatement("insert into line values (?, ?, ?, ?, ?, ?, ?, ?)")) {
            long lineId = 1;
            for (long id = 1; id <= ORDERS; id++) {
                o.setLong(1, id);
                o.setString(2, "customer " + id);
                o.addBatch();
                for (int n = 0; n < LINES; n++) {
                    l.setLong(1, lineId++);
                    l.setLong(2, id);
                    l.setString(3, "product " + ((id + n) % 500));
                    l.setInt(4, n + 1);
                    l.setBigDecimal(5, new BigDecimal("9.99").add(BigDecimal.valueOf(n)));
                    l.setTimestamp(6, Timestamp.valueOf(LocalDateTime.of(2024, 1, 1, 10, 0).plusMinutes(n)));
                    l.setBoolean(7, n % 2 == 0);
                    l.setDate(8, java.sql.Date.valueOf(LocalDate.of(2024, 2, 1).plusDays(n)));
                    l.addBatch();
                }
            }
            o.executeBatch();
            l.executeBatch();
        }
    }
}
