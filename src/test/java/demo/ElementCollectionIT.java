package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import demo.elementcollection.*;
import dev.hurlee.hibernate.multiset.MultisetPersisterClassResolver;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SchemaToolingSettings;
import org.hibernate.cfg.StatisticsSettings;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.params.AfterParameterizedClassInvocation;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code @ElementCollection} (basic Set, embeddable List with @OrderColumn, basic Map) as multisets. */
@ParameterizedClass
@MethodSource("demo.TestDb#databases")
class ElementCollectionIT {

    @Parameter
    private TestDb.Db db;

    private static SessionFactory sf;
    private static Statistics stats;
    private static Long annId;
    private static Long dexId;

    @BeforeParameterizedClassInvocation
    static void setUp(TestDb.Db db) throws Exception {
        TestDb.connect(db).close();
        sf = new Configuration()
                .addAnnotatedClass(Customer.class)
                .setProperty(JdbcSettings.JAKARTA_JDBC_URL, db.url())
                .setProperty(JdbcSettings.JAKARTA_JDBC_USER, db.user())
                .setProperty(JdbcSettings.JAKARTA_JDBC_PASSWORD, db.password())
                .setProperty("hibernate.persister.resolver", MultisetPersisterClassResolver.class.getName())
                .setProperty(SchemaToolingSettings.HBM2DDL_AUTO, "create-drop")
                .setProperty(StatisticsSettings.GENERATE_STATISTICS, true)
                .setProperty(JdbcSettings.SHOW_SQL, true)
                .setProperty(JdbcSettings.FORMAT_SQL, true)
                .buildSessionFactory();
        stats = sf.getStatistics();

        sf.inTransaction(s -> {
            Customer ann = new Customer("ann");
            ann.getPhoneNumbers().addAll(Set.of("+976 11", "+976 22", "+976 33"));
            ann.getAddresses().add(new Address("1 Main St", "Ulaanbaatar"));
            ann.getAddresses().add(new Address("2 Side St", null));
            ann.getAttributes().put("tier", "gold");
            ann.getAttributes().put("lang", "mn");
            s.persist(ann);
            annId = ann.getId();
            Customer bob = new Customer("bob");
            bob.getPhoneNumbers().add("+1 555");
            s.persist(bob);
            s.persist(new Customer("cid"));
            Customer dex = new Customer("dex"); // mutated by loadedElementCollections_areWritable only
            dex.getPhoneNumbers().addAll(Set.of("a", "b", "c"));
            dex.getAddresses().add(new Address("x", "X"));
            dex.getAddresses().add(new Address("y", "Y"));
            dex.getAttributes().put("tier", "gold");
            s.persist(dex);
            dexId = dex.getId();
        });
    }

    @AfterParameterizedClassInvocation
    static void tearDown() {
        sf.close();
    }

    @BeforeEach
    void clearStats() {
        stats.clear();
    }

    @Test
    void find_loadsAllThreeElementCollections_inOneStatement() {
        sf.inTransaction(s -> {
            Customer ann = s.find(Customer.class, annId);
            assertEquals(1, stats.getPrepareStatementCount());
            assertTrue(Hibernate.isInitialized(ann.getPhoneNumbers()));
            assertTrue(Hibernate.isInitialized(ann.getAddresses()));
            assertTrue(Hibernate.isInitialized(ann.getAttributes()));
            assertEquals(Set.of("+976 11", "+976 22", "+976 33"), ann.getPhoneNumbers());
            assertEquals(List.of(new Address("1 Main St", "Ulaanbaatar"), new Address("2 Side St", null)), ann.getAddresses());
            assertEquals(Map.of("tier", "gold", "lang", "mn"), ann.getAttributes());
            assertEquals(3, stats.getCollectionLoadCount());
        });
    }

    @Test
    void hql_allCustomers_inOneStatement() {
        sf.inTransaction(s -> {
            List<Customer> customers = s.createSelectionQuery("from Customer c order by c.name", Customer.class).list();
            assertEquals(1, stats.getPrepareStatementCount());
            assertEquals(List.of("ann", "bob", "cid", "dex"), customers.stream().map(Customer::getName).toList());
            assertEquals(List.of(3, 1, 0), customers.subList(0, 3).stream().map(c -> c.getPhoneNumbers().size()).toList());
            assertEquals(List.of(2, 0, 0), customers.subList(0, 3).stream().map(c -> c.getAddresses().size()).toList());
            assertEquals(List.of(2, 0, 0), customers.subList(0, 3).stream().map(c -> c.getAttributes().size()).toList());
        });
    }

    @Test
    void loadedElementCollections_areWritable() {
        sf.inTransaction(s -> {
            Customer dex = s.find(Customer.class, dexId);
            dex.getPhoneNumbers().remove("b");
            dex.getAddresses().add(0, dex.getAddresses().remove(1));
            dex.getAttributes().put("tier", "platinum");
        });
        sf.inTransaction(s -> {
            Customer dex = s.find(Customer.class, dexId);
            assertEquals(Set.of("a", "c"), dex.getPhoneNumbers());
            assertEquals(List.of("y", "x"), dex.getAddresses().stream().map(Address::getStreet).toList());
            assertEquals("platinum", dex.getAttributes().get("tier"));
        });
    }
}
