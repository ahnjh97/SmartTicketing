package smartticketing.booking;

import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Runs existing k6 query/login scripts against disposable data, never the developer database. */
public class QueryRedisBenchmark {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final LocalDate DATE=LocalDate.parse(System.getenv().getOrDefault("BENCH_DATE",LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(2).toString()));
    static final String PASSWORD="Benchmark-only-123!";
    record Fixture(long user,long movie,long theater,long show) {}
    record Scenario(String id,String name,String script) {}

    static Fixture seed(TemporaryMysqlDatabase db) {
        try(var em=db.open()) {
            em.getTransaction().begin();
            var user=BookingRedisBenchmark.user(em,1);
            Movie first=null;
            for(int n=0;n<200;n++) {
                var movie=new Movie(); movie.setTmdbMovieId(99000000L+n); movie.setTitle("부하 영화 "+n);
                movie.setRating("ALL"); movie.setRunningTime(120); movie.setAudienceCount(100000-n*100L);
                movie.setReleaseDate(DATE.minusDays(n<100?30:-30)); em.persist(movie);
                if(first==null) first=movie;
            }
            long theaterId=0,showId=0;
            for(int n=0;n<20;n++) {
                var theater=new Theater(); theater.setName("부하 극장 "+n); theater.setAddress("서울 "+n);
                theater.setBrand(TheaterBrand.CGV); theater.setKakaoPlaceId("benchmark-"+n);
                theater.setLatitude(BigDecimal.valueOf(37.5665+n*0.001));
                theater.setLongitude(BigDecimal.valueOf(126.9780+n*0.001)); em.persist(theater);
                var screen=new Screen(); screen.setName("부하 상영관"); screen.setTheater(theater); em.persist(screen);
                var show=new Showtime(); show.setMovie(first); show.setScreen(screen);
                show.setStartTime(DATE.atTime(18,0)); show.setEndTime(DATE.atTime(20,0));
                show.setPricePerPerson(10000); show.setTotalSeats(100); show.setAvailableSeats(100);
                show.setCreatedAt(DATE.minusDays(2).atStartOfDay()); show.setUpdatedAt(show.getCreatedAt()); em.persist(show);
                for(int s=0;s<100;s++) {
                    var seat=new Seat(); seat.setScreen(screen); seat.setSeatRow(String.valueOf((char)('A'+s/10)));
                    seat.setSeatNumber(s%10+1); seat.setSeatPosition(SeatPosition.MIDDLE_MIDDLE);
                    seat.setAdjacencySegment("row-"+s/10); seat.setPositionInSegment(s%10+1); em.persist(seat);
                    var inventory=new ShowtimeSeat(); inventory.setShowtime(show); inventory.setSeat(seat);
                    inventory.setStatus(SeatStatus.AVAILABLE); em.persist(inventory);
                }
                if(n==0) { theaterId=theater.getId(); showId=show.getId(); }
            }
            em.getTransaction().commit();
            return new Fixture(user.getId(),first.getId(),theaterId,showId);
        }
    }

    public static void main(String[] args) throws Exception {
        if(!"true".equals(System.getenv("BOOKING_LOAD_TEST"))) throw new IllegalStateException("Explicit local load opt-in required");
        String suite=System.getenv("BENCH_SUITE");
        boolean smoke="true".equals(System.getenv("BOOKING_LOAD_SMOKE"));
        var scenarios=new ArrayList<Scenario>();
        var canonical=Set.of("main","movies","theaters","showtimes","seats","distance");
        for(var item:JSON.readTree(Files.readString(Path.of("scripts/benchmark/suites.json")))) {
            String id=item.path("id").asText();
            if(id.equals(suite) || (suite.equals("queries-login") && canonical.contains(id))
                    || (suite.equals("queries-login") && id.equals("login"))) {
                if(item.has("script")) scenarios.add(new Scenario(id,item.path("name").asText(),item.path("script").asText()));
            }
        }
        if(scenarios.isEmpty()) throw new IllegalArgumentException("Unknown query suite: "+suite);
        Path output=Path.of(System.getenv("BOOKING_LOAD_OUTPUT")); Files.createDirectories(output);
        boolean child="true".equals(System.getenv("BENCH_CHILD"));
        if(!child) {
        Files.writeString(output.resolve("environment.json"),JSON.writeValueAsString(Map.of(
                "suite",suite,"smoke",smoke,"java",System.getProperty("java.version"),"os",System.getProperty("os.name"),
                "startedAt",OffsetDateTime.now(ZoneId.of("Asia/Seoul")).toString(),
                "logicalProcessors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),
                "fixture",Map.of("movies",200,"theaters",20,"showtimes",20,"seats",2000,"localCache",true,"localTtlMs",500,"redisTtlMs",2000,"freshJvmPerCase",true,"execution","production-prebuilt-jar"),
                "cacheMode",BenchmarkProcess.cacheMode(),
                "plannedExecutions",scenarios.stream().filter(s->!s.id().equals("login")).count()*BenchmarkProcess.modes(smoke,false).size()
                        +(scenarios.stream().anyMatch(s->s.id().equals("login"))?1:0))));
        var executions=new ArrayList<Map<String,Object>>();
        Files.writeString(output.resolve("executions.json"),"[]");
        for(var scenario:scenarios) {
            var order=BenchmarkProcess.modes(smoke,scenario.id().equals("login"));
            for(int i=0;i<order.size();i++) {
                boolean enabled=order.get(i);
                String label=String.format("%02d-%s",i+1,enabled?"on":"off");
                BenchmarkProcess.run(QueryRedisBenchmark.class,Map.of("BENCH_SUITE",scenario.id(),"BENCH_RUN_LABEL",label,
                        "BENCH_CACHE_ENABLED",Boolean.toString(enabled),"BENCH_DATE",DATE.toString()));
                @SuppressWarnings("unchecked")
                var result=(Map<String,Object>)JSON.readValue(Files.readString(output.resolve(label).resolve(scenario.id()+"-execution.json")),Map.class);
                executions.add(result);
                Files.writeString(output.resolve("executions.json"),JSON.writeValueAsString(executions));
            }
        }
        return;
        }
        var modes=List.of(Boolean.parseBoolean(System.getenv("BENCH_CACHE_ENABLED")));
        int run=0;
        for(boolean enabled:modes) {
            ++run;
            String label=System.getenv("BENCH_RUN_LABEL");
            Path dir=output.resolve(label); Files.createDirectories(dir);
            try(var db=new TemporaryMysqlDatabase()) {
                var fixture=seed(db);
                var props=List.of("--server.address=0.0.0.0","--server.port=8080",
                        "--tmdb.auto-import=false","--kakao.catalog.auto-import=false","--booking.seed.enabled=false","--showtime.seed.enabled=false",
                        "--app.reference-date="+DATE.minusDays(2),"--app.admin.initial-password=","--spring.jpa.show-sql=false","--spring.jpa.hibernate.ddl-auto=validate",
                        "--app.cache.enabled="+enabled,"--spring.data.redis.host=redis","--spring.data.redis.port=6379",
                        "--app.cache.main-local-ttl-ms=500","--app.cache.query-ttl-ms=2000",
                        "--spring.datasource.url="+db.jdbcUrl(),"--spring.datasource.username="+System.getenv("BOOKING_TEST_MYSQL_USER"),
                        "--JWT_SECRET="+BenchmarkBackend.SECRET,"--TMDB_ACCESS_TOKEN=local-test","--ADMIN_KEY=local-test",
                        "--logging.level.root=WARN");
                try(var backend=new BenchmarkBackend(props,dir.resolve("backend-"+suite))) {
                    try(var em=db.open()) {
                        em.getTransaction().begin();
                        em.find(Users.class,fixture.user()).setPassword(new smartticketing.config.PasswordEncoderConfig().passwordEncoder().encode(PASSWORD));
                        em.getTransaction().commit();
                    }
                    String token=BenchmarkBackend.jwt().issueAccessToken(fixture.user());
                    for(var scenario:scenarios) {
                        // Login is independent of the cache switch; run once, including in the all suite.
                        if(scenario.id().equals("login") && run!=1) continue;
                        var warmup=execute(scenario,dir,"warmup-"+scenario.id(),true,enabled,smoke,fixture,token);
                        var result=execute(scenario,dir,scenario.id(),false,enabled,smoke,fixture,token);
                        result.put("run",label); result.put("mode",scenario.id().equals("login")?"N/A":enabled?"ON":"OFF");
                        result.put("warmupFile",warmup.get("file")); result.put("warmupExitCode",warmup.get("exitCode"));
                        result.put("containerId",backend.containerId); result.put("pid",ProcessHandle.current().pid()); result.put("database",db.jdbcUrl());
                        result.put("cache",Map.of("redis",enabled,"local",true,"localTtlMs",500,"redisTtlMs",2000));
                        Files.writeString(dir.resolve(scenario.id()+"-execution.json"),JSON.writeValueAsString(result));
                    }
                }
            }
        }
        System.out.println("QUERY_BENCH_COMPLETE "+output);
    }

    static Map<String,Object> execute(Scenario scenario,Path dir,String name,boolean warmup,boolean enabled,
            boolean smoke,Fixture fixture,String token) throws Exception {
        Path summary=dir.resolve(name+".json");
        var command=new ArrayList<>(List.of("run","--no-usage-report","--quiet"));
        if(scenario.id().equals("login")) command.addAll(List.of("--summary-export",summary.toAbsolutePath().toString()));
        command.add(Path.of("k6",scenario.script()).toString());
        var env=new HashMap<String,String>();
        env.put("BASE_URL",BenchmarkBackend.BASE_URL); env.put("CACHE_MODE",enabled?"on":"off");
        env.put("RESULT_FILE",summary.toAbsolutePath().toString());
        env.put("REDIS_BENCH_RATE",smoke?"2":System.getenv().getOrDefault("BENCH_RATE","50"));
        env.put("REDIS_BENCH_DURATION",smoke?"3s":warmup?"15s":System.getenv().getOrDefault("BENCH_DURATION","30s"));
        env.put("REDIS_BENCH_PRE_VUS",smoke?"2":System.getenv().getOrDefault("BENCH_PRE_VUS","100"));
        env.put("REDIS_BENCH_MAX_VUS",smoke?"5":System.getenv().getOrDefault("BENCH_MAX_VUS","1000"));
        env.put("K6_MOVIE_ID",Long.toString(fixture.movie())); env.put("K6_THEATER_ID",Long.toString(fixture.theater()));
        env.put("K6_SHOWTIME_ID",Long.toString(fixture.show())); env.put("K6_DATE",DATE.toString()); env.put("K6_ACCESS_TOKEN",token);
        env.put("USERS",warmup||smoke?"5":System.getenv().getOrDefault("BENCH_LOGIN_USERS","100"));
        env.put("SPREAD",warmup||smoke?"1":"10"); env.put("LOGIN_ID","load-1"); env.put("PASSWORD",PASSWORD);
        System.out.println("QUERY_CASE "+dir.getFileName()+" "+name);
        long start=System.nanoTime(); int code=BenchmarkBackend.k6(command,env,dir.resolve(name+".log"));
        if(!Files.exists(summary) || (code!=0 && code!=99)) throw new IllegalStateException("k6 execution failed: "+name+" exit="+code);
        var result=new LinkedHashMap<String,Object>(); result.put("id",scenario.id()); result.put("name",scenario.name());
        result.put("script",scenario.script()); result.put("exitCode",code); result.put("wallSeconds",(System.nanoTime()-start)/1e9);
        result.put("file",dir.getFileName()+"/"+name+".json");
        System.out.println("QUERY_DONE "+dir.getFileName()+" "+name+" exit="+code);
        return result;
    }
}
