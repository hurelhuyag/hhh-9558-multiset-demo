package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import demo.onetomany.*;
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

/**
 * {@code @OneToMany(fetch = EAGER) @MultisetFetch}: real managed child entities, loaded from an
 * {@code array_agg(ROW(...))} column of the owner's statement — no join, no extra statements —
 * using only Hibernate's persister/fetch extension points (no hibernate-core changes).
 */
@ParameterizedClass
@MethodSource("demo.TestDb#databases")
class MultisetFetchIT {

    @Parameter
    private TestDb.Db db;

    private static SessionFactory sf;
    private static Statistics stats;
    private static Long aliceId;
    private static Long emptyId;

    @BeforeParameterizedClassInvocation
    static void setUp(TestDb.Db db) throws Exception {
        TestDb.connect(db).close();
        sf = new Configuration()
                .addAnnotatedClass(Author.class)
                .addAnnotatedClass(Book.class)
                .addAnnotatedClass(Review.class)
                .addAnnotatedClass(Publisher.class)
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
            Publisher acme = new Publisher("ACME");
            s.persist(acme);
            Author alice = new Author("alice")
                    .addBook("A", 100).addBook("B, with \"quotes\"", 200).addBook("C", 300)
                    .addReview("great", 5).addReview("meh", null);
            alice.getBooks().get(0).setPublisher(acme);
            Author bob = new Author("bob").addBook("D", 400).addReview("ok", 3);
            Author empty = new Author("carol");
            s.persist(alice);
            s.persist(bob);
            s.persist(empty);
            aliceId = alice.getId();
            emptyId = empty.getId();
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
    void find_loadsBothBags_inOneStatement() {
        sf.inTransaction(s -> {
            Author alice = s.find(Author.class, aliceId);

            assertEquals(1, stats.getPrepareStatementCount(), "owner + both collections must come from ONE statement");
            assertTrue(Hibernate.isInitialized(alice.getBooks()));
            assertTrue(Hibernate.isInitialized(alice.getReviews()));
            assertEquals(List.of("A", "B, with \"quotes\"", "C"), alice.getBooks().stream().map(Book::getTitle).toList());
            assertEquals(List.of(100, 200, 300), alice.getBooks().stream().map(Book::getPages).toList());
            assertEquals(List.of("great", "meh"), alice.getReviews().stream().map(Review::getText).toList());
            assertEquals(5, alice.getReviews().get(0).getStars());
            assertNull(alice.getReviews().get(1).getStars());

            // children are managed and wired back to the owner
            Book first = alice.getBooks().get(0);
            assertTrue(s.contains(first));
            assertSame(alice, first.getAuthor());
            assertSame(first, s.find(Book.class, first.getId()));
            assertEquals(1, stats.getPrepareStatementCount(), "find(Book) must hit the persistence context");
            assertEquals(2, stats.getCollectionLoadCount());

            // lazy to-one inside the element: uninitialised proxy carrying the FK from the record
            assertNotNull(first.getPublisher());
            assertEquals(false, Hibernate.isInitialized(first.getPublisher()));
            assertEquals("ACME", first.getPublisher().getName());
            assertEquals(2, stats.getPrepareStatementCount(), "proxy initialisation is the second statement");
            assertNull(alice.getBooks().get(1).getPublisher());
        });
    }

    @Test
    void orderColumn_isRespected_andMaintained() {
        sf.inTransaction(s -> {
            Author alice = s.find(Author.class, aliceId);
            alice.getBooks().add(0, alice.getBooks().remove(2)); // C, A, B
        });
        sf.inTransaction(s -> {
            Author alice = s.find(Author.class, aliceId);
            assertEquals(List.of("C", "A", "B, with \"quotes\""), alice.getBooks().stream().map(Book::getTitle).toList());
            alice.getBooks().add(1, alice.getBooks().remove(0)); // restore A, C, B -> A, C, B? no: back to original order
            alice.getBooks().add(2, alice.getBooks().remove(1));
        });
        sf.inTransaction(s -> {
            Author alice = s.find(Author.class, aliceId);
            assertEquals(List.of("A", "B, with \"quotes\"", "C"), alice.getBooks().stream().map(Book::getTitle).toList());
        });
    }

    @Test
    void hql_loadsAllOwnersAndBags_inOneStatement() {
        sf.inTransaction(s -> {
            List<Author> authors = s.createSelectionQuery("from Author a order by a.name", Author.class).list();

            assertEquals(1, stats.getPrepareStatementCount());
            assertEquals(3, authors.size());
            assertEquals(List.of(3, 1, 0), authors.stream().map(a -> a.getBooks().size()).toList());
            assertEquals(List.of(2, 1, 0), authors.stream().map(a -> a.getReviews().size()).toList());
            for (Author a : authors) {
                assertTrue(Hibernate.isInitialized(a.getBooks()));
                assertTrue(Hibernate.isInitialized(a.getReviews()));
                for (Book b : a.getBooks()) {
                    assertSame(a, b.getAuthor());
                }
            }
        });
    }

    @Test
    void emptyCollections_areInitializedEmpty() {
        sf.inTransaction(s -> {
            Author carol = s.find(Author.class, emptyId);
            assertTrue(Hibernate.isInitialized(carol.getBooks()));
            assertEquals(0, carol.getBooks().size());
            assertEquals(0, carol.getReviews().size());
            assertEquals(1, stats.getPrepareStatementCount());
        });
    }

    @Test
    void loadedCollections_supportDirtyChecking() {
        sf.inTransaction(s -> {
            Author alice = s.find(Author.class, aliceId);
            alice.getBooks().remove(1);
            alice.addBook("E", 500);
        });
        sf.inTransaction(s -> {
            Author alice = s.find(Author.class, aliceId);
            assertEquals(List.of("A", "C", "E"), alice.getBooks().stream().map(Book::getTitle).toList());
            assertNotNull(alice.getBooks().get(2).getId());
        });
    }

    @Test
    void explicitJoinFetch_stillWorks() {
        sf.inTransaction(s -> {
            Author alice = s.createSelectionQuery("from Author a join fetch a.books where a.id = :id", Author.class)
                    .setParameter("id", aliceId).getSingleResult();
            assertEquals(3, alice.getBooks().size());
            assertTrue(Hibernate.isInitialized(alice.getReviews()), "the other bag is still multiset-fetched");
            assertEquals(1, stats.getPrepareStatementCount());
        });
    }
}
