package smartticketing.booking;

import jakarta.persistence.EntityManager;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.BenchmarkSeatLayout;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Local opt-in only. Each run owns a fresh UUID schema and its scoped Redis keys. */
public class BookingRedisBenchmark {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final LocalDateTime NOW=LocalDateTime.parse(System.getenv().getOrDefault("BENCH_NOW",LocalDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0).toString()));
    record Reader(long user,long group,int ahead) {}
    record Release(long owner,long reservation,long user,long group,long seat) {}
    static Users user(EntityManager em,int n) {
        var u=new Users(); u.setName("부하 관객 "+n);u.setNickname("부하 "+n);u.setLoginId("load-"+n);
        u.setBirthDate(LocalDate.of(1990,1,1));em.persist(u);return u;
    }
    static BookingRequestGroup group(EntityManager em,Users u,Movie m,Showtime s,boolean smart) {
        var g=new BookingRequestGroup();g.setUser(u);g.setMovie(m);g.setSelectedShowtime(s);
        g.setEntryPoint(smart?BookingEntryPoint.THEATER_SMART:BookingEntryPoint.THEATER_NORMAL);g.setViewingDate(s.getStartTime().toLocalDate());g.setPartySize(1);
        if(smart) { g.setCandidateKind("PREFERRED");g.setCandidateZone(SeatPosition.MIDDLE_MIDDLE);g.getSeatPreferences().add(SeatPosition.MIDDLE_MIDDLE); }
        g.setAdultCount(1);g.setYouthCount(0);g.setCompanionsEligible(true);g.setGuardianAccompanying(false);g.setRatingSnapshot("ALL");
        g.setCreatedAt(NOW);g.setUpdatedAt(NOW);em.persist(g);return g;
    }
    static WaitingQueue queue(EntityManager em,Users u,BookingRequestGroup g,Showtime s,Seat seat,int n) {
        var q=new WaitingQueue();q.setUser(u);q.setRequestGroup(g);q.setShowtime(s);q.setSeatZone(SeatPosition.MIDDLE_MIDDLE);
        q.setQueueNumber(n);q.setZoneQueueNumber(n);q.setCreatedAt(NOW);q.setUpdatedAt(NOW);
        if(g.getEntryPoint()==BookingEntryPoint.THEATER_NORMAL) q.getRequestedSeatIds().add(seat.getId());
        em.persist(q);return q;
    }
    public static void main(String[] args) throws Exception {
        if(!"true".equals(System.getenv("BOOKING_LOAD_TEST"))) throw new IllegalStateException("Explicit local load opt-in required");
        Path output=Path.of(System.getenv("BOOKING_LOAD_OUTPUT")).toAbsolutePath();Files.createDirectories(output);
        boolean child="true".equals(System.getenv("BENCH_CHILD"));
        if(!child)
        Files.writeString(output.resolve("environment.json"),JSON.writeValueAsString(Map.of(
                "java",System.getProperty("java.version"),"os",System.getProperty("os.name"),
                "logicalProcessors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),
                "startedAt",OffsetDateTime.now(ZoneId.of("Asia/Seoul")).toString(),"port",8080,"freshJvmPerCase",true,"cacheMode",BenchmarkProcess.cacheMode(),"execution","production-prebuilt-jar")));
        boolean smoke="true".equals(System.getenv("BOOKING_LOAD_SMOKE"));
        boolean diagnostic="true".equals(System.getenv("BOOKING_LOAD_DIAGNOSTIC"));
        boolean core="true".equals(System.getenv("BENCH_CORE"));
        var modes=BenchmarkProcess.modes(smoke||diagnostic,false);
        if(!child) {
            var scenarios=core?("waiting".equals(System.getenv("BENCH_BOOKING_CASE"))?List.of("waiting"):
                    "dispatch".equals(System.getenv("BENCH_BOOKING_CASE"))?List.of("dispatch"):List.of("waiting","dispatch")):smoke?List.of("dispatch"):diagnostic?List.of("waiting-800","dispatch"):
                    List.of("waiting-100","waiting-400","waiting-800","status-400","dispatch");
            for(String scenario:scenarios) for(int i=0;i<modes.size();i++) {
                boolean enabled=modes.get(i);
                BenchmarkProcess.run(BookingRedisBenchmark.class,Map.of("BENCH_CASE",scenario,
                        "BENCH_CACHE_ENABLED",Boolean.toString(enabled),"BENCH_RUN_LABEL",String.format("%02d-%s",i+1,enabled?"on":"off"),
                        "BENCH_NOW",NOW.toString()));
            }
            return;
        }
        modes=List.of(Boolean.parseBoolean(System.getenv("BENCH_CACHE_ENABLED")));
        int run=0;
        for(boolean enabled:modes) {
            String label=System.getenv("BENCH_RUN_LABEL");Path dir=output.resolve(label);Files.createDirectories(dir);
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
                        var show=new Showtime();show.setMovie(movie);show.setScreen(screen);show.setStartTime(NOW.plusDays(2).withHour(18));show.setEndTime(show.getStartTime().plusHours(2));show.setPricePerPerson(10000);show.setTotalSeats(BenchmarkSeatLayout.size());show.setAvailableSeats(0);show.setCreatedAt(NOW);show.setUpdatedAt(NOW);em.persist(show);
                        var layout=BenchmarkSeatLayout.create(screen);layout.forEach(em::persist);
                        var seat=layout.stream().filter(s->s.getSeatRow().equals("D") && s.getSeatNumber()==4).findFirst().orElseThrow();
                        ShowtimeSeat inventory=null;
                        for(var physical:layout) {
                            var row=new ShowtimeSeat();row.setShowtime(show);row.setSeat(physical);row.setStatus(SeatStatus.BLOCKED);
                            if(physical==seat) { inventory=row;if(index>=4) row.setStatus(SeatStatus.HOLDING); }
                            em.persist(row);
                        }
                        Users owner=null;Reservation reservation=null;
                        if(index>=4) {
                            owner=user(em,++userNumber);var g=group(em,owner,movie,show,false);g.setStatus(BookingGroupStatus.HOLDING);
                            reservation=new Reservation();reservation.setUser(owner);reservation.setRequestGroup(g);reservation.setShowtime(show);reservation.setReservationType(ReservationType.NORMAL);reservation.setStatus(ReservationStatus.PENDING);reservation.setTotalAmount(10000);reservation.setExpiresAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusMinutes(5));reservation.setCreatedAt(NOW);reservation.setUpdatedAt(NOW);em.persist(reservation);
                            var rs=new ReservationSeat();rs.setReservation(reservation);rs.setSeat(seat);rs.setAudienceType("ADULT");rs.setPrice(10000);em.persist(rs);
                            var hold=new BookingGroupHold();hold.setRequestGroup(g);hold.setReservation(reservation);hold.setExpiresAt(reservation.getExpiresAt());em.persist(hold);
                            inventory.setReservation(reservation);inventory.setHoldExpiredAt(reservation.getExpiresAt());
                        }
                        for(int n=1;n<=(index<4?250:4);n++) {
                            var u=user(em,++userNumber);var g=group(em,u,movie,show,core);queue(em,u,g,show,seat,n);
                            if(index<4) readers.add(new Reader(u.getId(),g.getId(),n-1));
                            else if(n==1) releases.add(new Release(owner.getId(),reservation.getId(),u.getId(),g.getId(),seat.getId()));
                        }
                    }
                    em.getTransaction().commit();
                }
                var props=new ArrayList<>(List.of("--server.address=0.0.0.0","--server.port=8080",
                    "--tmdb.auto-import=false","--kakao.catalog.auto-import=false","--booking.seed.enabled=false","--showtime.seed.enabled=false",
                    "--app.admin.initial-password=","--spring.jpa.show-sql=false","--spring.jpa.hibernate.ddl-auto=validate",
                    "--app.cache.enabled="+enabled,"--spring.data.redis.host=redis","--spring.data.redis.port=6379",
                    "--app.cache.main-local-ttl-ms=500","--app.cache.query-ttl-ms=2000",
                    "--spring.datasource.url="+db.jdbcUrl(),"--spring.datasource.username="+System.getenv("BOOKING_TEST_MYSQL_USER"),
                    "--JWT_SECRET="+BenchmarkBackend.SECRET,"--TMDB_ACCESS_TOKEN=local-test","--ADMIN_KEY=local-test",
                    "--logging.level.root=WARN"));
                try(var backend=new BenchmarkBackend(props,dir.resolve("backend-"+System.getenv("BENCH_CASE")))) {
                    var jwt=BenchmarkBackend.jwt();var data=new LinkedHashMap<String,Object>();
                    data.put("users",readers.stream().map(r->Map.of("token",jwt.issueAccessToken(r.user()),"group",r.group(),"ahead",r.ahead())).toList());
                    data.put("dispatch",releases.stream().map(r->Map.of("token",jwt.issueAccessToken(r.user()),"ownerToken",jwt.issueAccessToken(r.owner()),"reservation",r.reservation(),"group",r.group(),"seat",r.seat())).toList());
                    Path fixture=dir.resolve("fixture.json");Files.writeString(fixture,JSON.writeValueAsString(data));
                    var cases=smoke?List.of(new String[]{"warmup","waiting","5","3s"},new String[]{"dispatch","dispatch","4","1s"}):diagnostic?
                        List.of(new String[]{"warmup","waiting","20","5s"},new String[]{"waiting-800","waiting","800","25s"},new String[]{"dispatch","dispatch","80","1s"}):
                        List.of(new String[]{"warmup","waiting","20","15s"},new String[]{"waiting-100","waiting","100","25s"},new String[]{"waiting-400","waiting","400","25s"},new String[]{"waiting-800","waiting","800","25s"},new String[]{"status-400","status","400","20s"},new String[]{"dispatch","dispatch","80","1s"});
                    if(core) cases=List.of(new String[]{"warmup","waiting",smoke?"2":"20",smoke?"3s":"15s"},
                            new String[]{"waiting","waiting",smoke?"2":"100",smoke?"3s":System.getenv().getOrDefault("BENCH_DURATION","60s")},
                            new String[]{"dispatch","dispatch",smoke?"4":"80","1s"});
                    String selected=System.getenv("BENCH_CASE");
                    cases=cases.stream().filter(c->c[0].equals("warmup")||c[0].equals(selected)).toList();
                    cases.getFirst()[0]="warmup-"+selected;
                    if(selected.equals("status-400")) cases.getFirst()[1]="status";
                    Files.writeString(dir.resolve(selected+"-isolation.json"),JSON.writeValueAsString(Map.of(
                            "containerId",backend.containerId,"pid",ProcessHandle.current().pid(),"database",db.jdbcUrl(),"redis",enabled,
                            "localCache",!core,"localTtlMs",core?0:500,"queryTtlMs",2000,"freshJvm",true)));
                    for(var c:cases) {
                        System.out.println("BENCH_CASE "+label+" "+c[0]);
                        long wallStart=System.nanoTime();
                        var env=new HashMap<String,String>();
                        env.put("BASE_URL",BenchmarkBackend.BASE_URL);env.put("FIXTURE",fixture.toString());env.put("CASE",c[1]);env.put("VUS",c[2]);env.put("DURATION",c[3]);env.put("SUMMARY",dir.resolve(c[0]+".json").toString());
                        if(core) {
                            env.put("BENCH_REQUEST_METRICS","true");
                            env.put("READ_ITERATIONS",smoke?"8":c[0].startsWith("warmup")?"40":"1000");
                            env.put("RATE",smoke?"2":System.getenv().getOrDefault("BENCH_RATE","50"));
                            env.put("PRE_VUS",smoke?"20":System.getenv().getOrDefault("BENCH_PRE_VUS","100"));
                            env.put("MAX_VUS",smoke?"20":System.getenv().getOrDefault("BENCH_MAX_VUS","1000"));
                        }
                        int code=BenchmarkBackend.k6(List.of("run","--no-usage-report","--quiet","k6/redis-comparison.js"),env,dir.resolve(c[0]+".log"));
                        System.out.println("BENCH_DONE "+label+" "+c[0]+" exit="+code);
                        Files.writeString(dir.resolve(c[0]+"-runtime.json"),JSON.writeValueAsString(Map.of(
                                "exitCode",code,"wallSeconds",(System.nanoTime()-wallStart)/1e9,
                                "runtimeMetrics","JVM internals not sampled across containers")));
                        if(code!=0 && code!=99) throw new IllegalStateException("k6 execution failed: "+dir.resolve(c[0]+".log"));
                        if(c[0].startsWith("warmup")) {
                            var metrics=JSON.readTree(Files.readString(dir.resolve(c[0]+".json"))).path("metrics");
                            var success=metrics.path("operation_success").path("values");
                            if(code!=0||success.path("passes").asLong()==0||success.path("fails").asLong()!=0
                                    ||metrics.path("http_req_failed").path("values").path("rate").asDouble()!=0)
                                throw new IllegalStateException("Smoke/warmup checks failed");
                        }
                    }
                    var validation=new LinkedHashMap<String,Object>();validation.put("mode",enabled);validation.put("readers",readers.size());validation.put("dispatchCases",releases.size());
                    try(var em=db.open()) {
                        long assigned=em.createQuery("select count(g) from BookingRequestGroup g where g.id in :ids and g.status=:s",Long.class).setParameter("ids",releases.stream().map(Release::group).toList()).setParameter("s",BookingGroupStatus.HOLDING).getSingleResult();
                        validation.put("assigned",assigned);
                        var duplicate=em.createNativeQuery("select rs.seat_id from reservation_seats rs join reservations r on r.id=rs.reservation_id where r.status in ('PENDING','CONFIRMED') group by r.showtime_id,rs.seat_id having count(*)>1",Object.class).getResultList();
                        validation.put("duplicateActiveSeats",duplicate.size());
                        validation.put("outboxPending",em.createNativeQuery("select count(*) from booking_outbox_events where status<>'COMPLETED'",Long.class).getSingleResult());
                    }
                    Files.writeString(dir.resolve(selected.equals("dispatch")?"validation.json":selected+"-validation.json"),JSON.writeValueAsString(validation));
                }
                Files.deleteIfExists(dir.resolve("fixture.json"));
                System.out.println("BENCH_CLEANED "+label);
            }
        }
        System.out.println("BENCH_COMPLETE "+output);
    }
}
