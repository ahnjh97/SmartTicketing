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
        // Simulate a catalog collected by the old application, with all queries complete.
        try (var em = database.open()) {
            em.getTransaction().begin();
            for (String name : List.of("CGV 압구정", "CGV 압구정 본관", "CGV씨네드쉐프 압구정")) {
                var row = new Theater();
                row.setKakaoPlaceId("legacy-" + name); row.setName(name); row.setBrand(TheaterBrand.CGV);
                row.setAddress(name.endsWith("본관") ? "서울 강남구 논현로 848" : "서울 강남구 압구정로30길 45");
                em.persist(row);
            }
            em.getTransaction().commit();
        }
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
            var legacy = em.createQuery("from Theater t where t.kakaoPlaceId like 'legacy-%'", Theater.class).getResultList();
            assertThat(legacy).hasSize(3);
            assertThat(legacy).filteredOn(Theater::isActive).extracting(Theater::getName).containsExactly("CGV 압구정");
        }
    }

    @Test void legacyAliasAndItsReferencesSurviveRefreshWithoutCreatingNewAliases() {
        var alias = branchPlace("alias-yongsan", "CGV 씨네드쉐프용산", "서울 용산구 한강대로23길 55");
        var otherAlias = branchPlace("alias-yongsan-long", "CGV 씨네드쉐프 용산아이파크몰", "서울 용산구 한강대로23길 55");
        var representative = branchPlace("branch-yongsan", "CGV 용산아이파크몰", "서울 용산구 한강대로23길 55");
        Long aliasId;
        Long screenId;
        try (var em = database.open()) {
            em.getTransaction().begin();
            var row = new Theater();
            row.setKakaoPlaceId(alias.kakaoPlaceId()); row.setName(alias.name());
            row.setBrand(alias.brand()); row.setAddress(alias.address());
            em.persist(row);
            aliasId = row.getId();
            var screen = new smartticketing.entity.Screen();
            screen.setTheater(row); screen.setName("기존 상영관");
            em.persist(screen); em.getTransaction().commit();
            screenId = screen.getId();
        }
        writer.savePage("branch-pages", 1, true, List.of(representative));
        database.restartPersistence(); setup();
        writer.reset(List.of("branch-pages"));
        // Reverse order during refresh, including an additional formatting variant.
        writer.savePage("branch-pages", 1, true, List.of(representative, alias, otherAlias,
                branchPlace("alias-yongsan-new", "cgv 씨네드쉐프 용산", "서울특별시 용산구 한강대로23길 55")));
        assertThat(writer.consolidateBranches()).isZero();
        try (var em = database.open()) {
            var rows = em.createQuery("from Theater t where t.address like '%용산구%'", Theater.class).getResultList();
            assertThat(rows).hasSize(2);
            assertThat(rows).filteredOn(Theater::isActive).extracting(Theater::getKakaoPlaceId)
                    .containsExactly(representative.kakaoPlaceId());
            assertThat(em.find(Theater.class, aliasId).isActive()).isFalse();
            assertThat(em.find(smartticketing.entity.Screen.class, screenId).getTheater().getId()).isEqualTo(aliasId);
            em.getTransaction().begin();
            rows.stream().filter(Theater::isActive).forEach(row -> row.setActive(false));
            em.getTransaction().commit();
        }
        writer.reset(List.of("branch-pages"));
        writer.savePage("branch-pages", 1, true, List.of(alias, representative));
        try (var em = database.open()) {
            assertThat(em.createQuery("from Theater t where t.address like '%용산구%'", Theater.class)
                    .getResultList()).hasSize(2).noneMatch(Theater::isActive);
        }
    }

    @Test void freshCollectionNeverInsertsExcludedFacilitiesEvenBeforeRepresentatives() {
        var aliases = List.of(
                branchPlace("fresh-alias-1", "CGV 씨네드쉐프 용산아이파크몰", "서울 용산구 한강대로23길 55"),
                branchPlace("fresh-alias-2", "cgv 씨네드쉐프용산", "서울특별시 용산구 한강대로23길 55"),
                branchPlace("fresh-alias-3", "CGV 압구정 본관", "서울 강남구 논현로 848"),
                branchPlace("fresh-alias-4", "CGV씨네드쉐프 압구정", "서울 강남구 압구정로30길 45"));
        var counts = writer.savePage("fresh", 1, false, aliases);
        assertThat(counts.inserted()).isZero();
        assertThat(counts.updated()).isZero();
        try (var em = database.open()) {
            assertThat(em.createQuery("select count(t) from Theater t where t.kakaoPlaceId like 'fresh-alias-%'", Long.class)
                    .getSingleResult()).isZero();
            assertThat(em.find(TheaterCollectionProgress.class, "fresh").getNextPage()).isEqualTo(2);
        }
        writer.savePage("fresh", 2, true, List.of(place("fresh-normal", "CGV 강남")));
        writer.reset(List.of("fresh"));
        writer.savePage("fresh", 1, true, aliases);
        try (var em = database.open()) {
            assertThat(em.createQuery("select count(t) from Theater t where t.kakaoPlaceId like 'fresh-alias-%'", Long.class)
                    .getSingleResult()).isZero();
        }
    }

    @Test void separateBranchesAndUnverifiedAddressesAreNotConsolidated() {
        var boutique = new TheaterPlace("separate-boutique", "메가박스 더부티크 목동현대백화점",
                TheaterBrand.MEGABOX, "서울 양천구 목동동로 257", new BigDecimal("37.5"), new BigDecimal("127.0"));
        var mokdong = new TheaterPlace("separate-mokdong", "메가박스 목동",
                TheaterBrand.MEGABOX, "서울 양천구 목동동로 309", boutique.latitude(), boutique.longitude());
        writer.savePage("separate", 1, true, List.of(boutique, mokdong,
                branchPlace("separate-cgv", "CGV 홍대", "서울 마포구 양화로 153"),
                branchPlace("separate-unknown", "CGV 압구정 본관", "서울 강남구 다른주소 1")));
        try (var em = database.open()) {
            assertThat(em.createQuery("from Theater t where t.kakaoPlaceId like 'separate-%'", Theater.class)
                    .getResultList()).hasSize(4).allMatch(Theater::isActive);
        }
    }

    private TheaterPlace branchPlace(String id, String name, String address) {
        return new TheaterPlace(id, name, TheaterBrand.CGV, address, new BigDecimal("37.5"), new BigDecimal("127.0"));
    }
}
