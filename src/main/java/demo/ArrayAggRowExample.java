package demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone JDBC example of the MULTISET emulation with {@code array_agg(ROW(...))}.
 * <p>
 * Loads every animal together with two child collections (locations, tags) in ONE statement and
 * ONE result-set row per animal. Compare with a double LEFT JOIN, which would return
 * {@code locations x tags} rows per animal.
 * <p>
 * The {@code record[]} columns are requested in binary wire format and decoded with
 * {@link PgBinaryRecordArray}: every field arrives with its type OID, so the values come out
 * typed ({@code Integer}, {@code String}, ...) without any string parsing.
 * <p>
 * Run against any PostgreSQL:
 * <pre>
 *   java -cp target/classes:postgresql.jar -Dpg.url=jdbc:postgresql://localhost:5432/demo \
 *        -Dpg.user=demo -Dpg.password=demo demo.ArrayAggRowExample
 * </pre>
 * or simply {@code mvn verify}, which runs it against the container started by docker-maven-plugin.
 */
public class ArrayAggRowExample {

    private static final String SQL = """
        select a.ani_id,
               a.ani_name,
               (select array_agg(row(l.loc_id, l.loc_name, l.loc_description) order by l.loc_id)
                  from location l
                 where l.ani_id = a.ani_id) as locations,
               (select array_agg(row(t.tag_id, t.tag) order by t.tag_id)
                  from tag t
                 where t.ani_id = a.ani_id) as tags
          from animal a
         order by a.ani_id
        """;

    public record Location(int id, String name, String description) { }
    public record Tag(int id, String tag) { }
    public record Animal(int id, String name, List<Location> locations, List<Tag> tags) { }

    public static void main(String[] args) throws Exception {
        String url = System.getProperty("pg.url", "jdbc:postgresql://localhost:5432/demo");
        String user = System.getProperty("pg.user", "demo");
        String password = System.getProperty("pg.password", "demo");

        // binary transfer for record[] (oid 2287), from the first execution of a PreparedStatement
        String binaryUrl = url + (url.contains("?") ? "&" : "?") + "binaryTransferEnable=2287&prepareThreshold=-1";

        try (Connection c = connect(binaryUrl, user, password)) {
            createSampleData(c);

            try (PreparedStatement ps = c.prepareStatement(SQL); ResultSet rs = ps.executeQuery()) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    // pgjdbc cannot decode an anonymous record, but hands out the raw binary payload
                    Object[][] locations = PgBinaryRecordArray.read(rs, "locations");
                    Object[][] tags = PgBinaryRecordArray.read(rs, "tags");

                    Animal animal = new Animal(
                            rs.getInt("ani_id"),
                            rs.getString("ani_name"),
                            Arrays.stream(locations)
                                    .map(f -> new Location((Integer) f[0], (String) f[1], (String) f[2]))
                                    .toList(),
                            Arrays.stream(tags)
                                    .map(f -> new Tag((Integer) f[0], (String) f[1]))
                                    .toList());

                    System.out.println("decoded locations = " + Arrays.deepToString(locations));
                    System.out.println("decoded tags      = " + Arrays.deepToString(tags));
                    System.out.println("mapped            = " + animal);
                    System.out.println();
                }
                System.out.println(rows + " result-set rows for " + rows + " animals (no cartesian product)");
            }
        }
    }

    /** A freshly started PostgreSQL opens its TCP port a moment before it accepts logins. */
    private static Connection connect(String url, String user, String password) throws Exception {
        SQLException last = null;
        for (int i = 0; i < 30; i++) {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw last;
    }

    private static void createSampleData(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("drop table if exists tag, location, animal");
            st.execute("create table animal(ani_id int primary key, ani_name text)");
            st.execute("create table location(loc_id int primary key, ani_id int references animal, loc_name text, loc_description text)");
            st.execute("create table tag(tag_id int primary key, ani_id int references animal, tag text)");
            st.execute("insert into animal values (1, 'cat'), (2, 'dog'), (3, 'fish')");
            // NULL, comma, quotes and backslash: no quoting rules to worry about in binary format
            st.execute("insert into location values (10, 1, 'sofa', 'warm'), (11, 1, 'window', null), (12, 2, 'yard', 'muddy, wet')");
            st.execute("insert into tag values (100, 1, 'fluffy'), (101, 1, 'says \"meow\"'), (102, 1, 'back\\slash'), (103, 2, 'good boy')");
        }
    }
}
