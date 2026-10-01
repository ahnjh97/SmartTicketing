package smartticketing.booking;

import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import smartticketing.entity.Theater;
import smartticketing.entity.TheaterCollectionProgress;
import smartticketing.entity.enums.TheaterBrand;
import smartticketing.repository.TheaterRepository;
import smartticketing.repository.TheaterCollectionProgressRepository;
import smartticketing.service.SeoulTheaterCollectionService.TheaterPlace;
import smartticketing.service.TheaterCatalogWriter;
import smartticketing.service.SeoulTheaterCollectionService;
import smartticketing.config.TheaterDataInitializer;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TheaterCatalogWriterTests {
    private static TemporaryMysqlDatabase database;
    private TheaterCatalogWriter writer;
    @BeforeAll static void database() throws Exception { database = new TemporaryMysqlDatabase(); }
    @AfterAll static void close() throws Exception { if (database != null) database.close(); }
    @BeforeEach void setup() {
        var em = SharedEntityManagerCreator.createSharedEntityManager(database.factory());
        var repos = new JpaRepositoryFactory(em);
        var target = new TheaterCatalogWriter(repos.getRepository(TheaterRepository.class),
                repos.getRepository(TheaterCollectionProgressRepository.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new JpaTransactionManager(database.factory()),
                new AnnotationTransactionAttributeSource()));
        writer = (TheaterCatalogWriter) proxy.getProxy();
    }

    private TheaterPlace place(String id, String name) {
        return new TheaterPlace(id, name, TheaterBrand.CGV, "서울 강남구", new BigDecimal("37.5"), new BigDecimal("127.0"));
    }

    @Test void progressAndCatalogPersistAcrossRestartAndRefreshPreservesDisabledTheater() {
        writer.savePage("persist", 1, true, List.of(place("persist", "CGV 기존")));
        database.restartPersistence(); setup();
        try (var em = database.open()) {
            assertThat(em.find(TheaterCollectionProgress.class, "persist").isComplete()).isTrue();
            em.getTransaction().begin();
            var row = em.createQuery("from Theater t where t.kakaoPlaceId = 'persist'", Theater.class).getSingleResult();
            row.setActive(false); em.getTransaction().commit();
        }
        writer.reset(List.of("persist"));
        var result = writer.savePage("persist", 1, true, List.of(place("persist", "CGV 변경")));
        assertThat(result.inserted()).isZero(); assertThat(result.updated()).isEqualTo(1);
        try (var em = database.open()) {
            var rows = em.createQuery("from Theater t where t.kakaoPlaceId = 'persist'", Theater.class).getResultList();
            assertThat(rows).hasSize(1); assertThat(rows.getFirst().isActive()).isFalse();
            assertThat(rows.getFirst().getName()).isEqualTo("CGV 변경");
        }
    }

    @Test void failedPageRollsBackBothEarlierInsertsAndProgress() {
        assertThatThrownBy(() -> writer.savePage("rollback", 1, true,
                List.of(place("rollback-good", "CGV 정상"), place("rollback-bad", null))))
                .isInstanceOf(RuntimeException.class);
        try (var em = database.open()) {
            assertThat(em.find(TheaterCollectionProgress.class, "rollback")).isNull();
            assertThat(em.createQuery("select count(t) from Theater t where t.kakaoPlaceId like 'rollback-%'", Long.class)
                    .getSingleResult()).isZero();
        }
    }

    @Test void realStartupCollectionMakesNoExternalCallsAfterPersistenceRestart() {
        var builder = RestClient.builder().baseUrl("https://kakao.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = builder.build();
        server.expect(org.springframework.test.web.client.ExpectedCount.times(75),
                org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                    {"meta":{"is_end":true},"documents":[
                      {"id":"startup","place_name":"CGV 강남","road_address_name":"서울 강남구",
                       "x":"127.0","y":"37.5"}]}
                    """, MediaType.APPLICATION_JSON));
        var repo = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(database.factory()))
                .getRepository(TheaterCollectionProgressRepository.class);
        new TheaterDataInitializer(new SeoulTheaterCollectionService(repo, writer, client, "test"), true, "test").run(null);
        server.verify(); server.reset();
        database.restartPersistence(); setup();
        repo = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(database.factory()))
                .getRepository(TheaterCollectionProgressRepository.class);
        var service = new SeoulTheaterCollectionService(repo, writer, client, "test");
        new TheaterDataInitializer(service, true, "test").run(null);
        assertThat(service.collectSeoulTheaters()).containsEntry("apiCallCount", 0).containsEntry("skippedQueryCount", 75);
        server.verify();
        try (var em = database.open()) {
            assertThat(em.createQuery("select count(t) from Theater t where t.kakaoPlaceId = 'startup'", Long.class)
                    .getSingleResult()).isEqualTo(1);
        }
    }
}
