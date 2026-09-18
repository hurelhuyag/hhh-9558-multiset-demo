package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import demo.pgenum.Genre;
import demo.pgenum.Shelf;
import demo.pgenum.Volume;
import dev.hurlee.hibernate.multiset.MultisetPersisterClassResolver;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SchemaToolingSettings;
import org.hibernate.cfg.StatisticsSettings;
import org.junit.jupiter.api.Test;

/** A PostgreSQL native enum ({@code NAMED_ENUM}) inside the record: unknown OID to the decoder, sent as its label. */
class PostgresNamedEnumIT {

    @Test
    void namedEnum_insideTheRecord() throws Exception {
        TestDb.connect().close();
        try (SessionFactory sf = new Configuration()
                .addAnnotatedClass(Shelf.class).addAnnotatedClass(Volume.class)
                .setProperty(JdbcSettings.JAKARTA_JDBC_URL, TestDb.BINARY_URL)
                .setProperty(JdbcSettings.JAKARTA_JDBC_USER, TestDb.USER)
                .setProperty(JdbcSettings.JAKARTA_JDBC_PASSWORD, TestDb.PASSWORD)
                .setProperty("hibernate.persister.resolver", MultisetPersisterClassResolver.class.getName())
                .setProperty(SchemaToolingSettings.HBM2DDL_AUTO, "create-drop")
                .setProperty(StatisticsSettings.GENERATE_STATISTICS, true)
                .setProperty(JdbcSettings.SHOW_SQL, true)
                .buildSessionFactory()) {
            Long[] id = new Long[1];
            sf.inTransaction(s -> {
                Shelf shelf = new Shelf("A1").add("Dune", Genre.FICTION).add("Cosmos", Genre.SCIENCE).add("untyped", null);
                s.persist(shelf);
                id[0] = shelf.getId();
            });
            sf.getStatistics().clear();
            sf.inTransaction(s -> {
                Shelf shelf = s.find(Shelf.class, id[0]);
                assertEquals(1, sf.getStatistics().getPrepareStatementCount());
                assertEquals(List.of(Genre.FICTION, Genre.SCIENCE), shelf.getVolumes().subList(0, 2).stream().map(Volume::getGenre).toList());
                assertNull(shelf.getVolumes().get(2).getGenre());
            });
        }
    }
}
