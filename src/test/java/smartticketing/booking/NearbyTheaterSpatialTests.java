package smartticketing.booking;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import smartticketing.config.TheaterSpatialSchema;
import smartticketing.entity.Theater;
import smartticketing.entity.enums.TheaterBrand;
import smartticketing.service.NearbyTheaterQuery;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class NearbyTheaterSpatialTests {
    private static TemporaryMysqlDatabase db;
    private EntityManager em;
    @BeforeAll static void start() throws Exception {
        db=new TemporaryMysqlDatabase();
        try (var em=db.open()) { em.unwrap(Session.class).doWork(TheaterSpatialSchema::install); }
    }
    @AfterAll static void stop() throws Exception { if(db!=null)db.close(); }
    @BeforeEach void begin() { em=db.open(); em.getTransaction().begin(); }
    @AfterEach void rollback() { if(em.getTransaction().isActive())em.getTransaction().rollback(); em.close(); }

    private Theater theater(String name,Double lat,Double lon) {
        var t=new Theater(); t.setName(name); t.setAddress("극장 주소"); t.setBrand(TheaterBrand.CGV);
        t.setKakaoPlaceId(java.util.UUID.randomUUID().toString());
        if(lat!=null)t.setLatitude(BigDecimal.valueOf(lat)); if(lon!=null)t.setLongitude(BigDecimal.valueOf(lon));
        em.persist(t); return t;
    }
    private List<Long> nearest(double lat,double lon) {
        em.flush();
        return new NearbyTheaterQuery(em).findNearest(lat,lon).stream().map(NearbyTheaterQuery.Match::id).toList();
    }
    private List<Long> exhaustive(double lat,double lon) {
        return em.createNativeQuery("""
                select t.id from theaters t where t.is_active=true
                and t.latitude between -90 and 90 and t.longitude between -180 and 180
                order by st_distance_sphere(point(if(t.longitude=-180,180,t.longitude),t.latitude),point(:lon,:lat),6371000),t.name,t.id
                limit 12
                """,Long.class).setParameter("lat",lat).setParameter("lon",lon).getResultList();
    }

    @Test void nationwideSearchReturnsGlobalNearestTwelveInsteadOfRectangleCorners() {
        // Dense corners can be farther than points outside the initial search rectangle.
        for(int i=0;i<25;i++)theater("모서리 "+i,37.5840+i*0.000001,127.0000);
        for(int i=0;i<10;i++)theater("북쪽 "+i,37.5850+i*0.0001,126.9780);
        for(int i=0;i<30;i++)theater("전국 "+i,33.0+i*0.16,126.5+i*0.09);
        theater("좌표 없음",null,null);
        var inactive=theater("비활성",37.5665,126.9780); inactive.setActive(false);
        assertThat(nearest(37.5665,126.9780)).hasSize(12).containsExactlyElementsOf(exhaustive(37.5665,126.9780));
        assertThat(nearest(35.1796,129.0756)).containsExactlyElementsOf(exhaustive(35.1796,129.0756));
    }

    @Test void sparseCatalogExpandsBeyondSeoulAndHandlesEmptyAndMissingCoordinates() {
        assertThat(nearest(37.5665,126.9780)).isEmpty();
        theater("부산",35.1796,129.0756); theater("제주",33.4996,126.5312);
        theater("위도 없음",null,127.0); theater("잘못된 좌표",91.0,127.0);
        assertThat(nearest(37.5665,126.9780)).hasSize(2).containsExactlyElementsOf(exhaustive(37.5665,126.9780));
    }

    @Test void coordinateChangesRemovalAndDeletionUpdateSpatialProjection() {
        var t=theater("이동",37.5665,126.9780); em.flush();
        assertThat(new NearbyTheaterQuery(em).findNearest(37.5665,126.9780).getFirst().distanceMeters()).isZero();
        t.setLatitude(BigDecimal.valueOf(35.1796)); t.setLongitude(BigDecimal.valueOf(129.0756)); em.flush();
        assertThat(new NearbyTheaterQuery(em).findNearest(35.1796,129.0756).getFirst().distanceMeters()).isZero();
        t.setLatitude(null); assertThat(nearest(35.1796,129.0756)).isEmpty();
        t.setLatitude(BigDecimal.valueOf(35.1796)); assertThat(nearest(35.1796,129.0756)).containsExactly(t.getId());
        em.remove(t); assertThat(nearest(35.1796,129.0756)).isEmpty();
    }

    @Test void dateLineAndPolarSearchesKeepGlobalNearestOrder() {
        for(int i=0;i<25;i++)theater("동쪽 "+i,1.0+i*0.01,179.99);
        for(int i=0;i<25;i++)theater("서쪽 "+i,1.0+i*0.01,-179.99);
        theater("극점",90.0,0.0); theater("날짜 경계",0.0,-180.0);
        assertThat(nearest(1,180)).containsExactlyElementsOf(exhaustive(1,180));
        assertThat(nearest(89.99,40)).containsExactlyElementsOf(exhaustive(89.99,40));
        assertThat(nearest(0,-180)).containsExactlyElementsOf(exhaustive(0,180));
    }

    @Test void spatialRangeScanUsesIndexAndNeverHydratesTheaterEntities() {
        for(int i=0;i<200;i++)theater("전국 "+i,33.0+i*0.025,126.0+i*0.01);
        theater("서울",37.5665,126.9780); em.flush(); em.clear();
        var stats=db.factory().getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        nearest(37.5665,126.9780);
        assertThat(stats.getEntityLoadCount()).isZero();
        var plan=em.createNativeQuery("explain format=json "+NearbyTheaterQuery.SEARCH_SQL,String.class)
                .setParameter("latitude",37.5665).setParameter("longitude",126.9780).setParameter("radius",2000)
                .setParameter("bounds","POLYGON((126.95 37.54,127.01 37.54,127.01 37.59,126.95 37.59,126.95 37.54))")
                .getSingleResult().toString();
        assertThat(plan).contains("\"key\": \"idx_theater_locations_point\"", "\"access_type\": \"range\"");
    }

    @Test void invalidInputIsRejectedBeforeSql() {
        assertThatThrownBy(() -> nearest(Double.NaN,127)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> nearest(91,127)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> nearest(37,181)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void installationBackfillsExistingCoordinatesAndCanBeRepeated() throws Exception {
        try(var legacy=new TemporaryMysqlDatabase(); var old=legacy.open()) {
            old.getTransaction().begin();
            var t=new Theater();t.setName("기존");t.setBrand(TheaterBrand.CGV);t.setAddress("서울");t.setKakaoPlaceId("legacy");
            t.setLatitude(BigDecimal.valueOf(37.5));t.setLongitude(BigDecimal.valueOf(127));old.persist(t);
            old.getTransaction().commit();
            old.unwrap(Session.class).doWork(TheaterSpatialSchema::install);
            old.unwrap(Session.class).doWork(TheaterSpatialSchema::install);
            old.getTransaction().begin();
            assertThat(new NearbyTheaterQuery(old).findNearest(37.5,127)).extracting(NearbyTheaterQuery.Match::id).containsExactly(t.getId());
            old.getTransaction().rollback();
        }
    }
}
