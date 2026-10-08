package smartticketing.booking;

import jakarta.persistence.EntityManager;
import org.springframework.boot.SpringApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import smartticketing.SmartTicketingApplication;
import smartticketing.auth.JwtService;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Local opt-in only. Each run owns a fresh UUID schema and its scoped Redis keys. */
public class BookingRedisBenchmark {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final LocalDateTime NOW=LocalDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0);
    record Reader(long user,long group,int ahead) {}
    record Release(long owner,long reservation,long user,long group,long seat) {}
    static Users user(EntityManager em,int n) {
        var u=new Users(); u.setName("부하 관객 "+n);u.setNickname("부하 "+n);u.setLoginId("load-"+n);
        u.setBirthDate(LocalDate.of(1990,1,1));em.persist(u);return u;
    }
    static BookingRequestGroup group(EntityManager em,Users u,Movie m,Showtime s) {
        var g=new BookingRequestGroup();g.setUser(u);g.setMovie(m);g.setSelectedShowtime(s);
        g.setEntryPoint(BookingEntryPoint.THEATER_NORMAL);g.setViewingDate(s.getStartTime().toLocalDate());g.setPartySize(1);
        g.setAdultCount(1);g.setYouthCount(0);g.setCompanionsEligible(true);g.setGuardianAccompanying(false);g.setRatingSnapshot("ALL");
        g.setCreatedAt(NOW);g.setUpdatedAt(NOW);em.persist(g);return g;
    }
    static WaitingQueue queue(EntityManager em,Users u,BookingRequestGroup g,Showtime s,Seat seat,int n) {
        var q=new WaitingQueue();q.setUser(u);q.setRequestGroup(g);q.setShowtime(s);q.setSeatZone(SeatPosition.MIDDLE_MIDDLE);
        q.setQueueNumber(n);q.setZoneQueueNumber(n);q.setCreatedAt(NOW);q.setUpdatedAt(NOW);q.getRequestedSeatIds().add(seat.getId());em.persist(q);return q;
    }
    public static void main(String[] args) throws Exception {
        if(!"true".equals(System.getenv("BOOKING_LOAD_TEST"))) throw new IllegalStateException("Explicit local load opt-in required");
        Path output=Path.of(System.getenv("BOOKING_LOAD_OUTPUT")).toAbsolutePath();Files.createDirectories(output);
        Path k6=Path.of(System.getenv("K6_BINARY")).toAbsolutePath();
        Files.writeString(output.resolve("environment.json"),JSON.writeValueAsString(Map.of(
                "java",System.getProperty("java.version"),"os",System.getProperty("os.name"),
                "logicalProcessors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),
                "startedAt",OffsetDateTime.now(ZoneId.of("Asia/Seoul")).toString(),"port",18081)));
        boolean smoke="true".equals(System.getenv("BOOKING_LOAD_SMOKE"));
        boolean diagnostic="true".equals(System.getenv("BOOKING_LOAD_DIAGNOSTIC"));
        var modes=smoke||diagnostic?List.of(false,true):List.of(false,true,true,false);
        int run=0;
        for(boolean enabled:modes) {
            String label=String.format("%02d-%s",++run,enabled?"on":"off");Path dir=output.resolve(label);Files.createDirectories(dir);
            System.out.println("BENCH_START "+label);
            var readers=new ArrayList<Reader>();var releases=new ArrayList<Release>();
            try(var db=new TemporaryMysqlDatabase()) {
                try(var em=db.open()) {
                    em.getTransaction().begin();
                    var movie=new Movie();movie.setTmdbMovieId(99000001L);movie.setTitle("격리 부하 영화");movie.setRating("ALL");movie.setRunningTime(120);em.persist(movie);
                    var theater=new Theater();theater.setName("격리 부하 극장");theater.setAddress("서울");theater.setBrand(TheaterBrand.CGV);theater.setKakaoPlaceId("load-local");em.persist(theater);
                    int userNumber=0;
                    int dispatchShows=smoke?4:80;
                    for(int index=0;index<4+dispatchShows;index++) {
                        var screen=new Screen();screen.setName("부하 "+index);screen.setTheater(theater);em.persist(screen);
                        var show=new Showtime();show.setMovie(movie);show.setScreen(screen);show.setStartTime(NOW.plusDays(2).withHour(18));show.setEndTime(show.getStartTime().plusHours(2));show.setPricePerPerson(10000);show.setTotalSeats(1);show.setAvailableSeats(0);show.setCreatedAt(NOW);show.setUpdatedAt(NOW);em.persist(show);
                        var seat=new Seat();seat.setScreen(screen);seat.setSeatRow("A");seat.setSeatNumber(1);seat.setSeatPosition(SeatPosition.MIDDLE_MIDDLE);seat.setAdjacencySegment("a");seat.setPositionInSegment(1);em.persist(seat);
                        var inventory=new ShowtimeSeat();inventory.setShowtime(show);inventory.setSeat(seat);inventory.setStatus(index<4?SeatStatus.BLOCKED:SeatStatus.HOLDING);em.persist(inventory);
                        Users owner=null;Reservation reservation=null;
                        if(index>=4) {
                            owner=user(em,++userNumber);var g=group(em,owner,movie,show);g.setStatus(BookingGroupStatus.HOLDING);
                            reservation=new Reservation();reservation.setUser(owner);reservation.setRequestGroup(g);reservation.setShowtime(show);reservation.setReservationType(ReservationType.NORMAL);reservation.setStatus(ReservationStatus.PENDING);reservation.setTotalAmount(10000);reservation.setExpiresAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusMinutes(5));reservation.setCreatedAt(NOW);reservation.setUpdatedAt(NOW);em.persist(reservation);
                            var rs=new ReservationSeat();rs.setReservation(reservation);rs.setSeat(seat);rs.setAudienceType("ADULT");rs.setPrice(10000);em.persist(rs);
                            var hold=new BookingGroupHold();hold.setRequestGroup(g);hold.setReservation(reservation);hold.setExpiresAt(reservation.getExpiresAt());em.persist(hold);
                            inventory.setReservation(reservation);inventory.setHoldExpiredAt(reservation.getExpiresAt());
                        }
                        for(int n=1;n<=(index<4?250:4);n++) {
                            var u=user(em,++userNumber);var g=group(em,u,movie,show);queue(em,u,g,show,seat,n);
                            if(index<4) readers.add(new Reader(u.getId(),g.getId(),n-1));
                            else if(n==1) releases.add(new Release(owner.getId(),reservation.getId(),u.getId(),g.getId(),seat.getId()));
                        }
                    }
                    em.getTransaction().commit();
                }
                var props=new ArrayList<>(List.of("--server.address=127.0.0.1","--server.port=18081","--spring.profiles.active=test",
                    "--tmdb.auto-import=false","--kakao.catalog.auto-import=false","--booking.seed.enabled=false","--showtime.seed.enabled=false",
                    "--app.admin.initial-password=","--spring.jpa.show-sql=false","--spring.jpa.hibernate.ddl-auto=validate",
                    "--app.cache.enabled="+enabled,"--spring.data.redis.host=127.0.0.1","--spring.data.redis.port=6379",
                    "--spring.datasource.url="+db.jdbcUrl(),"--spring.datasource.username="+System.getenv("BOOKING_TEST_MYSQL_USER"),"--spring.datasource.password="+System.getenv("BOOKING_TEST_MYSQL_PASSWORD"),
                    "--JWT_SECRET=local-load-only-not-production-secret-2026-123456789","--TMDB_ACCESS_TOKEN=local-test","--ADMIN_KEY=local-test",
                    "--logging.level.root=WARN"));
                try(var app=SpringApplication.run(SmartTicketingApplication.class,props.toArray(String[]::new))) {
                    var web=(org.springframework.boot.tomcat.TomcatWebServer)app.getClass().getMethod("getWebServer").invoke(app);
                    var protocol=(org.apache.coyote.AbstractProtocol<?>)web.getTomcat().getConnector().getProtocolHandler();
                    Files.writeString(dir.resolve("connector.json"),JSON.writeValueAsString(Map.of(
                            "acceptCount",protocol.getAcceptCount(),"maxConnections",protocol.getMaxConnections(),"maxThreads",protocol.getMaxThreads())));
                    var jwt=app.getBean(JwtService.class);var data=new LinkedHashMap<String,Object>();
                    data.put("users",readers.stream().map(r->Map.of("token",jwt.issueAccessToken(r.user()),"group",r.group(),"ahead",r.ahead())).toList());
                    data.put("dispatch",releases.stream().map(r->Map.of("token",jwt.issueAccessToken(r.user()),"ownerToken",jwt.issueAccessToken(r.owner()),"reservation",r.reservation(),"group",r.group(),"seat",r.seat())).toList());
                    Path fixture=dir.resolve("fixture.json");Files.writeString(fixture,JSON.writeValueAsString(data));
                    var cases=smoke?List.of(new String[]{"warmup","waiting","5","3s"},new String[]{"dispatch","dispatch","4","1s"}):diagnostic?
                        List.of(new String[]{"warmup","waiting","20","5s"},new String[]{"waiting-800","waiting","800","25s"},new String[]{"dispatch","dispatch","80","1s"}):
                        List.of(new String[]{"warmup","waiting","20","15s"},new String[]{"waiting-100","waiting","100","25s"},new String[]{"waiting-400","waiting","400","25s"},new String[]{"waiting-800","waiting","800","25s"},new String[]{"status-400","status","400","20s"},new String[]{"dispatch","dispatch","80","1s"});
                    for(var c:cases) {
                        System.out.println("BENCH_CASE "+label+" "+c[0]);
                        var os=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                        long cpuStart=os.getProcessCpuTime(),wallStart=System.nanoTime();
                        var pb=new ProcessBuilder(k6.toString(),"run","--no-usage-report","--quiet",Path.of("k6/redis-comparison.js").toAbsolutePath().toString());
                        var env=pb.environment();env.put("FIXTURE",fixture.toString());env.put("CASE",c[1]);env.put("VUS",c[2]);env.put("DURATION",c[3]);env.put("SUMMARY",dir.resolve(c[0]+".json").toString());
                        pb.redirectErrorStream(true).redirectOutput(dir.resolve(c[0]+".log").toFile());
                        var peakConnections=new java.util.concurrent.atomic.AtomicLong();
                        var peakBusyThreads=new java.util.concurrent.atomic.AtomicLong();
                        var sampler=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                        sampler.scheduleAtFixedRate(() -> {
                            peakConnections.accumulateAndGet(protocol.getConnectionCount(),Math::max);
                            if(protocol.getExecutor() instanceof java.util.concurrent.ThreadPoolExecutor executor)
                                peakBusyThreads.accumulateAndGet(executor.getActiveCount(),Math::max);
                            else if(protocol.getExecutor() instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor executor)
                                peakBusyThreads.accumulateAndGet(executor.getActiveCount(),Math::max);
                        },0,200,java.util.concurrent.TimeUnit.MILLISECONDS);
                        int code;
                        try { code=pb.start().waitFor(); } finally { sampler.shutdownNow(); }
                        System.out.println("BENCH_DONE "+label+" "+c[0]+" exit="+code);
                        Files.writeString(dir.resolve(c[0]+"-runtime.json"),JSON.writeValueAsString(Map.of(
                                "exitCode",code,"wallSeconds",(System.nanoTime()-wallStart)/1e9,
                                "jvmCpuSeconds",(os.getProcessCpuTime()-cpuStart)/1e9,
                                "peakConnections",peakConnections.get(),"peakBusyThreads",peakBusyThreads.get())));
                        if(code!=0 && code!=99) throw new IllegalStateException("k6 execution failed: "+dir.resolve(c[0]+".log"));
                        if(c[0].equals("warmup") && code!=0) throw new IllegalStateException("Smoke/warmup checks failed");
                    }
                    var validation=new LinkedHashMap<String,Object>();validation.put("mode",enabled);validation.put("readers",readers.size());validation.put("dispatchCases",releases.size());
                    try(var em=db.open()) {
                        long assigned=em.createQuery("select count(g) from BookingRequestGroup g where g.id in :ids and g.status=:s",Long.class).setParameter("ids",releases.stream().map(Release::group).toList()).setParameter("s",BookingGroupStatus.HOLDING).getSingleResult();
                        validation.put("assigned",assigned);
                        var duplicate=em.createNativeQuery("select rs.seat_id from reservation_seats rs join reservations r on r.id=rs.reservation_id where r.status in ('PENDING','CONFIRMED') group by r.showtime_id,rs.seat_id having count(*)>1",Object.class).getResultList();
                        validation.put("duplicateActiveSeats",duplicate.size());
                        validation.put("outboxPending",em.createNativeQuery("select count(*) from booking_outbox_events where status<>'COMPLETED'",Long.class).getSingleResult());
                    }
                    Files.writeString(dir.resolve("validation.json"),JSON.writeValueAsString(validation));
                    // Remove only this temporary DB's namespaced booking Redis keys.
                    var redis=app.getBean(StringRedisTemplate.class);String scope=UUID.nameUUIDFromBytes(db.jdbcUrl().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    for(String prefix:List.of("booking-ranks:v1:","booking-dispatch:v1:","smart-summary:v1:")) {
                        var keys=redis.keys(prefix+scope+":*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
                    }
                }
                Files.deleteIfExists(dir.resolve("fixture.json"));
                System.out.println("BENCH_CLEANED "+label);
            }
        }
        System.out.println("BENCH_COMPLETE "+output);
    }
}
