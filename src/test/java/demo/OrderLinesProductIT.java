package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import demo.graph.*;
import dev.hurlee.hibernate.multiset.MultisetPersisterClassResolver;
import jakarta.persistence.EntityGraph;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SchemaToolingSettings;
import org.hibernate.cfg.StatisticsSettings;
import org.hibernate.graph.GraphSemantic;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.params.AfterParameterizedClassInvocation;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code Order -> lines -> product -> category}. {@code lines} and {@code product} are LAZY; applying
 * the named entity graph {@code Order.withProducts} loads lines and products as one multiset column
 * of the order statement. {@code category} (EAGER by default) follows the graph semantic.
 */
@ParameterizedClass
@MethodSource("demo.TestDb#databases")
class OrderLinesProductIT {

    @Parameter
    private TestDb.Db db;

    private static SessionFactory sf;
    private static Statistics stats;
    private static Long annId;

    @BeforeParameterizedClassInvocation
    static void setUp(TestDb.Db db) throws Exception {
        TestDb.connect(db).close();
        sf = new Configuration()
                .addAnnotatedClass(Order.class)
                .addAnnotatedClass(OrderLine.class)
                .addAnnotatedClass(Product.class)
                .addAnnotatedClass(Category.class)
                .addAnnotatedClass(Discount.class)
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
            Category fruit = new Category("fruit");
            s.persist(fruit);
            Product apple = new Product("APPLE", new BigDecimal("0.50"), fruit);
            Product pear = new Product("PEAR", new BigDecimal("0.75"), null);
            s.persist(apple);
            s.persist(pear);
            Order ann = new Order("ann").addLine(apple, 3).addLine(pear, 1).addLine(apple, 2);
            ann.getLines().get(0).discount("bulk", new BigDecimal("0.10")).discount("coupon", new BigDecimal("0.05"));
            ann.getLines().get(2).discount("loyalty", new BigDecimal("0.01"));
            s.persist(ann);
            annId = ann.getId();
            s.persist(new Order("ben").addLine(pear, 10).addLine(null, 1)); // a line without product
            s.persist(new Order("cid"));
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
    void withoutGraph_linesStayLazy() {
        sf.inTransaction(s -> {
            List<Order> orders = s.createSelectionQuery("from Order o order by o.customer", Order.class).list();
            assertEquals(1, stats.getPrepareStatementCount());
            assertFalse(Hibernate.isInitialized(orders.get(0).getLines()));

            Order ann = s.find(Order.class, annId);
            assertFalse(Hibernate.isInitialized(ann.getLines()));
            assertEquals(3, ann.getLines().size()); // plain lazy collection load
            assertEquals(2, stats.getPrepareStatementCount());
            assertFalse(Hibernate.isInitialized(ann.getLines().get(0).getProduct()));
        });
    }

    @Test
    void hql_withFetchGraph_loadsLinesAndProducts_inOneStatement() {
        sf.inTransaction(s -> {
            @SuppressWarnings("unchecked") EntityGraph<Order> graph = (EntityGraph<Order>) s.getEntityGraph(Order.WITH_PRODUCTS);
            List<Order> orders = s.createSelectionQuery("from Order o order by o.customer", Order.class)
                    .setEntityGraph(graph, GraphSemantic.FETCH)
                    .list();

            assertEquals(1, stats.getPrepareStatementCount(), "orders + lines + products in ONE statement");
            assertEquals(3, orders.size());
            Order ann = orders.get(0);
            assertTrue(Hibernate.isInitialized(ann.getLines()));
            assertEquals(List.of("APPLE", "PEAR", "APPLE"), ann.getLines().stream().map(l -> l.getProduct().getSku()).toList());
            assertEquals(List.of(3, 1, 2), ann.getLines().stream().map(OrderLine::getQuantity).toList());
            assertEquals(new BigDecimal("0.50"), ann.getLines().get(0).getProduct().getPrice());

            assertSame(ann.getLines().get(0).getProduct(), ann.getLines().get(2).getProduct());
            assertTrue(Hibernate.isInitialized(ann.getLines().get(0).getProduct()));
            assertSame(ann.getLines().get(1).getProduct(), orders.get(1).getLines().get(0).getProduct());
            assertSame(ann, ann.getLines().get(0).getOrder());
            assertNull(orders.get(1).getLines().get(1).getProduct());
            assertEquals(0, orders.get(2).getLines().size());

            // FETCH semantic: category is not in the graph -> lazy proxy, FK taken from the record
            assertFalse(Hibernate.isInitialized(ann.getLines().get(0).getProduct().getCategory()));
            assertEquals(1, stats.getPrepareStatementCount());
            assertEquals("fruit", ann.getLines().get(0).getProduct().getCategory().getName());
            assertEquals(2, stats.getPrepareStatementCount());
        });
    }

    @Test
    void hql_withLoadGraph_alsoFoldsEagerCategory() {
        sf.inTransaction(s -> {
            @SuppressWarnings("unchecked") EntityGraph<Order> graph = (EntityGraph<Order>) s.getEntityGraph(Order.WITH_PRODUCTS);
            Order ann = s.createSelectionQuery("from Order o where o.id = :id", Order.class)
                    .setParameter("id", annId)
                    .setEntityGraph(graph, GraphSemantic.LOAD)
                    .getSingleResult();
            // LOAD semantic: category keeps its EAGER default -> joined inside the multiset too
            assertTrue(Hibernate.isInitialized(ann.getLines().get(0).getProduct().getCategory()));
            assertEquals("fruit", ann.getLines().get(0).getProduct().getCategory().getName());
            assertNull(ann.getLines().get(1).getProduct().getCategory());
            assertEquals(1, stats.getPrepareStatementCount());
        });
    }

    @Test
    void find_withGraphHint_loadsLinesAndProducts_inOneStatement() {
        sf.inTransaction(s -> {
            @SuppressWarnings("unchecked") EntityGraph<Order> graph = (EntityGraph<Order>) s.getEntityGraph(Order.WITH_PRODUCTS);
            Order ann = s.find(Order.class, annId, Map.of(GraphSemantic.FETCH.getJakartaHintName(), graph));

            assertEquals(1, stats.getPrepareStatementCount());
            assertTrue(Hibernate.isInitialized(ann.getLines()));
            assertEquals(3, ann.getLines().size());
            assertTrue(Hibernate.isInitialized(ann.getLines().get(1).getProduct()));
            assertEquals("PEAR", ann.getLines().get(1).getProduct().getSku());
            assertEquals(1, stats.getPrepareStatementCount());
        });
    }

    @Test
    void nestedCollection_insideTheElement_isPartOfTheSameStatement() {
        sf.inTransaction(s -> {
            @SuppressWarnings("unchecked") EntityGraph<Order> graph = (EntityGraph<Order>) s.getEntityGraph(Order.WITH_PRODUCTS);
            Order ann = s.find(Order.class, annId, Map.of(GraphSemantic.LOAD.getJakartaHintName(), graph));
            assertEquals(1, stats.getPrepareStatementCount());
            assertTrue(Hibernate.isInitialized(ann.getLines().get(0).getDiscounts()));
            assertEquals(List.of("bulk", "coupon"), ann.getLines().get(0).getDiscounts().stream().map(Discount::getReason).toList());
            assertEquals(0, ann.getLines().get(1).getDiscounts().size());
            assertEquals(List.of("loyalty"), ann.getLines().get(2).getDiscounts().stream().map(Discount::getReason).toList());
            assertEquals(new BigDecimal("0.05"), ann.getLines().get(0).getDiscounts().get(1).getAmount());
            assertSame(ann.getLines().get(0), ann.getLines().get(0).getDiscounts().get(0).getLine());
            assertEquals(1, stats.getPrepareStatementCount(), "no statement per line for its discounts");
        });
    }

    @Test
    void explicitJoinFetch_isStillAJoin() {
        sf.inTransaction(s -> {
            Order ann = s.createSelectionQuery("from Order o join fetch o.lines l where o.id = :id", Order.class)
                    .setParameter("id", annId).getSingleResult();
            assertEquals(3, ann.getLines().size());
            assertEquals(1, stats.getPrepareStatementCount());
        });
    }
}
