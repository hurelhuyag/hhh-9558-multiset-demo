package demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Connection details for the PostgreSQL started by docker-maven-plugin (port injected by failsafe). */
final class TestDb {

    static final String URL = "jdbc:postgresql://localhost:" + System.getProperty("postgres.port", "5432") + "/demo";
    /** Same, with pgjdbc asked to transfer record[] (oid 2287) in binary format from the first execution. */
    static final String BINARY_URL = URL + "?binaryTransferEnable=2287&prepareThreshold=-1";
    static final String USER = "demo";
    static final String PASSWORD = "demo";

    private TestDb() {
    }

    /** The TCP port is open slightly before the server accepts logins, so retry briefly. */
    static Connection connect() throws SQLException, InterruptedException {
        SQLException last = null;
        for (int i = 0; i < 30; i++) {
            try {
                return DriverManager.getConnection(URL, USER, PASSWORD);
            } catch (SQLException e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw last;
    }
}
