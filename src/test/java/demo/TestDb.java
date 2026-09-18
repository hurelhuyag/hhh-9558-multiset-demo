package demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.stream.Stream;

/** Connection details for the databases started by docker-maven-plugin (ports injected by failsafe). */
final class TestDb {

    /** A database the ITs run against. */
    record Db(String name, String url, String user, String password) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final String URL = "jdbc:postgresql://localhost:" + System.getProperty("postgres.port", "5432") + "/demo";
    /** pgjdbc asked to transfer record[] (oid 2287) in binary format from the first execution. */
    static final String BINARY_URL = URL + "?binaryTransferEnable=2287&prepareThreshold=-1";
    static final String USER = "demo";
    static final String PASSWORD = "demo";

    static final Db POSTGRES = new Db("PostgreSQL", BINARY_URL, USER, PASSWORD);
    /** PostgreSQL without binary transfer: record[] arrives as a text literal. */
    static final Db POSTGRES_TEXT = new Db("PostgreSQL (text)", URL, USER, PASSWORD);
    static final Db MYSQL = new Db("MySQL",
            "jdbc:mysql://localhost:" + System.getProperty("mysql.port", "3306") + "/demo?allowPublicKeyRetrieval=true&useSSL=false",
            "root", "demo");
    /** json_arrayagg is built on group_concat: lift its 1 MiB default limit for the session. */
    static final Db MARIADB = new Db("MariaDB",
            "jdbc:mariadb://localhost:" + System.getProperty("mariadb.port", "3306") + "/demo?sessionVariables=group_concat_max_len=4294967295",
            "root", "demo");

    /** No server at all: a file database under the build directory (each IT class gets its own). */
    static final Db SQLITE = new Db("SQLite",
            "jdbc:sqlite:" + System.getProperty("java.io.tmpdir") + "/hibernate-multiset-" + ProcessHandle.current().pid() + ".db",
            "", "");

    private TestDb() {
    }

    /** The databases every multiset case runs against ({@code @ParameterizedClass} source). */
    static Stream<Db> databases() {
        return Stream.of(SQLITE, POSTGRES, MYSQL, MARIADB);
    }

    /** PostgreSQL, for the PostgreSQL-only tests. */
    static Connection connect() throws SQLException, InterruptedException {
        return connect(POSTGRES);
    }

    /** The TCP port is open slightly before the server accepts logins, so retry briefly. */
    static Connection connect(Db db) throws SQLException, InterruptedException {
        SQLException last = null;
        for (int i = 0; i < 120; i++) {
            try {
                return DriverManager.getConnection(db.url(), db.user(), db.password());
            } catch (SQLException e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw last;
    }
}
