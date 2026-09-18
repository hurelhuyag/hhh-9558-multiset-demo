package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import demo.manytomany.*;
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

/** {@code Role <-> Permission} many-to-many as a multiset. */
@ParameterizedClass
@MethodSource("demo.TestDb#databases")
class ManyToManyIT {

    @Parameter
    private TestDb.Db db;

    private static SessionFactory sf;
    private static Statistics stats;
    private static Long adminId;
    private static Long editorId;

    @BeforeParameterizedClassInvocation
    static void setUp(TestDb.Db db) throws Exception {
        TestDb.connect(db).close();
        sf = new Configuration()
                .addAnnotatedClass(Role.class)
                .addAnnotatedClass(Permission.class)
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
            Permission read = new Permission("READ");
            Permission write = new Permission("WRITE");
            Permission admin = new Permission("ADMIN");
            s.persist(read);
            s.persist(write);
            s.persist(admin);
            Role adminRole = new Role("admin").grant(read).grant(write).grant(admin);
            Role editorRole = new Role("editor").grant(read).grant(write);   // shares READ and WRITE with admin
            s.persist(adminRole);
            s.persist(editorRole);
            s.persist(new Role("guest"));
            adminId = adminRole.getId();
            editorId = editorRole.getId();
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
    void allRoles_withPermissions_inOneStatement_sharedPermissionsAreSameInstance() {
        sf.inTransaction(s -> {
            List<Role> roles = s.createSelectionQuery("from Role r order by r.name", Role.class).list();
            assertEquals(1, stats.getPrepareStatementCount());
            assertEquals(List.of(3, 2, 0), roles.stream().map(r -> r.getPermissions().size()).toList());
            for (Role r : roles) {
                assertTrue(Hibernate.isInitialized(r.getPermissions()));
            }
            Role admin = roles.get(0);
            Role editor = roles.get(1);
            assertEquals(Set.of("READ", "WRITE", "ADMIN"), admin.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet()));
            Permission readViaAdmin = admin.getPermissions().stream().filter(p -> p.getCode().equals("READ")).findFirst().orElseThrow();
            Permission readViaEditor = editor.getPermissions().stream().filter(p -> p.getCode().equals("READ")).findFirst().orElseThrow();
            assertSame(readViaAdmin, readViaEditor, "one managed instance per permission");
            assertSame(readViaAdmin, s.find(Permission.class, readViaAdmin.getId()));
            assertEquals(1, stats.getPrepareStatementCount());
        });
    }

    @Test
    void find_andModify_joinTableRows() {
        sf.inTransaction(s -> {
            Role editor = s.find(Role.class, editorId);
            assertEquals(1, stats.getPrepareStatementCount());
            editor.getPermissions().removeIf(p -> p.getCode().equals("WRITE"));
            editor.grant(s.find(Role.class, adminId).getPermissions().stream().filter(p -> p.getCode().equals("ADMIN")).findFirst().orElseThrow());
        });
        sf.inTransaction(s -> {
            Role editor = s.find(Role.class, editorId);
            assertEquals(Set.of("READ", "ADMIN"), editor.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet()));
        });
    }
}
