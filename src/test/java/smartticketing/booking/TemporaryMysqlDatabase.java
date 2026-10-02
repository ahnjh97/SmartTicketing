package smartticketing.booking;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import java.sql.*;
import java.util.UUID;

/** 새 MySQL 테스트 DB만 소유한다. 생성 실패 시 기존 이름의 DB를 삭제하지 않는다. */
public final class TemporaryMysqlDatabase implements AutoCloseable {
    private final String name = "booking_test_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;
    private boolean created;
    private SessionFactory factory;
    private Configuration configuration;
    private java.util.function.Consumer<String> sqlObserver;

    public TemporaryMysqlDatabase(String... legacySetup) throws Exception {
        this(null, legacySetup);
    }

    TemporaryMysqlDatabase(java.util.function.Consumer<String> sqlObserver, String... legacySetup) throws Exception {
        this.sqlObserver = sqlObserver;
        String user = System.getenv("BOOKING_TEST_MYSQL_USER"), password = System.getenv("BOOKING_TEST_MYSQL_PASSWORD");
        if (user == null || user.isBlank() || password == null) throw new IllegalStateException("Set BOOKING_TEST_MYSQL_USER and BOOKING_TEST_MYSQL_PASSWORD");
        try {
            admin = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/", user, password);
            try (var stmt = admin.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE " + name + " CHARACTER SET utf8mb4");
                created = true;
            }
            if (legacySetup.length > 0) {
                admin.setCatalog(name);
                try (var stmt = admin.createStatement()) {
                    for (String sql : legacySetup) stmt.executeUpdate(sql);
                }
            }
            var config = new Configuration().setPhysicalNamingStrategy(new PhysicalNamingStrategySnakeCaseImpl())
                    .setProperty("hibernate.connection.driver_class", "com.mysql.cj.jdbc.Driver")
                    .setProperty("hibernate.connection.url", "jdbc:mysql://127.0.0.1:3306/" + name)
                    .setProperty("hibernate.connection.username", user)
                    .setProperty("hibernate.connection.password", password)
                    .setProperty("hibernate.hbm2ddl.auto", "update")
                    .setProperty("hibernate.hbm2ddl.halt_on_error", "true")
                    .setProperty("hibernate.show_sql", "false");
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
            for (var bean : scanner.findCandidateComponents("smartticketing.entity")) {
                config.addAnnotatedClass(Class.forName(bean.getBeanClassName()));
            }
            configuration = config;
            if (sqlObserver != null) config.setStatementInspector(sql -> { sqlObserver.accept(sql); return sql; });
            factory = config.buildSessionFactory();
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    EntityManager open() { return factory.createEntityManager(); }

    SessionFactory factory() { return factory; }
    public String jdbcUrl() { return "jdbc:mysql://127.0.0.1:3306/" + name; }

    /** DB 내용은 보존하고 앱의 ORM 연결/캐시만 재시작한다. */
    void restartPersistence() {
        factory.close();
        var fresh = new Configuration().setPhysicalNamingStrategy(new PhysicalNamingStrategySnakeCaseImpl());
        fresh.setProperties(configuration.getProperties());
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (var bean : scanner.findCandidateComponents("smartticketing.entity")) {
            try { fresh.addAnnotatedClass(Class.forName(bean.getBeanClassName())); }
            catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
        }
        if (sqlObserver != null) fresh.setStatementInspector(sql -> { sqlObserver.accept(sql); return sql; });
        configuration = fresh;
        factory = configuration.buildSessionFactory();
    }

    @Override public void close() throws SQLException {
        try { if (factory != null) factory.close(); }
        finally {
            if (admin != null && !admin.isClosed()) {
                try (var connection = admin; var stmt = connection.createStatement()) {
                    if (created) stmt.executeUpdate("DROP DATABASE " + name);
                }
            }
        }
    }
}
