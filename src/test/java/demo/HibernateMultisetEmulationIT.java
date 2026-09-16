package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SchemaToolingSettings;
import org.hibernate.cfg.StatisticsSettings;
import org.hibernate.loader.MultipleBagFetchException;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hibernate-level proof: two bag collections are loaded for every user in ONE SQL statement via
 * {@code @Formula} + {@code array_agg(ROW(...))} + {@link RecordArrayType} (binary wire format),
 * i.e. the MULTISET emulation from HHH-9558 works today on PostgreSQL with only user-level mapping.
 */
class HibernateMultisetEmulationIT {

    private static SessionFactory sf;
    private static Statistics stats;

    @BeforeAll
    static void setUp() throws Exception {
        TestDb.connect().close(); // wait until the container accepts logins
        sf = new Configuration()
                .addAnnotatedClass(User.class)
                .addAnnotatedClass(Phone.class)
                .addAnnotatedClass(Email.class)
                .setProperty(JdbcSettings.JAKARTA_JDBC_URL, TestDb.BINARY_URL)
                .setProperty(JdbcSettings.JAKARTA_JDBC_USER, TestDb.USER)
                .setProperty(JdbcSettings.JAKARTA_JDBC_PASSWORD, TestDb.PASSWORD)
                .setProperty(SchemaToolingSettings.HBM2DDL_AUTO, "create-drop")
                .setProperty(StatisticsSettings.GENERATE_STATISTICS, true)
                .setProperty(JdbcSettings.SHOW_SQL, true)
                .setProperty(JdbcSettings.FORMAT_SQL, true)
                .buildSessionFactory();
        stats = sf.getStatistics();

        sf.inTransaction(s -> {
            s.persist(new User("alice").addPhone("111").addPhone("222").addEmail("a@x").addEmail("alice@y").addEmail("al@z"));
            s.persist(new User("bob").addPhone("333").addEmail("b@x"));
            s.persist(new User("carol")); // no children at all
        });
    }

    @AfterAll
    static void tearDown() {
        sf.close();
    }

    @Test
    void joinFetchingTwoBags_isRefusedByHibernate() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> sf.inTransaction(s ->
                s.createSelectionQuery("from User u join fetch u.phones join fetch u.emails", User.class).list()));
        assertInstanceOf(MultipleBagFetchException.class, e.getCause());
    }

    @Test
    void multisetEmulation_loadsBothBags_inOneStatement_withOneRowPerUser() {
        stats.clear();
        List<User> users = sf.fromTransaction(s ->
                s.createSelectionQuery("from User u order by u.name", User.class).list());

        assertEquals(1, stats.getPrepareStatementCount(), "everything must come from a single SQL statement");
        assertEquals(3, users.size());

        User alice = users.get(0);
        assertEquals("alice", alice.getName());
        assertEquals(List.of("111", "222"), alice.getPhoneMultiset().stream().map(User.PhoneView::number).toList());
        assertEquals(List.of("a@x", "alice@y", "al@z"), alice.getEmailMultiset().stream().map(User.EmailView::address).toList());

        User bob = users.get(1);
        assertEquals(List.of("333"), bob.getPhoneMultiset().stream().map(User.PhoneView::number).toList());
        assertEquals(List.of("b@x"), bob.getEmailMultiset().stream().map(User.EmailView::address).toList());

        User carol = users.get(2);
        assertEquals(List.of(), carol.getPhoneMultiset());
        assertEquals(List.of(), carol.getEmailMultiset());

        // the emulated bags carry the real child ids, so they are usable as identifiers too
        sf.inTransaction(s -> {
            User reloaded = s.find(User.class, alice.getId());
            assertEquals(reloaded.getPhones().stream().map(Phone::getId).toList(),
                         reloaded.getPhoneMultiset().stream().map(User.PhoneView::id).toList());
        });
    }
}
