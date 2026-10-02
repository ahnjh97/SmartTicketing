package smartticketing.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import smartticketing.SmartTicketingApplication;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit opt-in browser harness. Owns only a fresh TemporaryMysqlDatabase. Not a JUnit test. */
public class BookingBrowserFixture {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("BOOKING_BROWSER_TEST"))) throw new IllegalStateException("Browser test opt-in required");
        String run = System.getenv().getOrDefault("BOOKING_BROWSER_RUN", "stage6");
        if (!run.matches("stage[0-9]+")) throw new IllegalArgumentException("Invalid browser run name");
        boolean waitingRun = run.equals("stage8") || run.equals("stage9");
        Path restart = Path.of(".gradle/" + run + "-browser.restart");
        Path stop = Path.of(".gradle/" + run + "-browser.stop");
        if (Files.exists(stop)) throw new IllegalStateException("Remove the previous browser stop marker before launch");
        try (var db = new TemporaryMysqlDatabase()) {
            var browserShows = new ArrayList<Long>();
            try (var em = db.open()) {
                em.getTransaction().begin(); var now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
                var user = new Users(); user.setName("격리 관객"); user.setNickname("격리 관객"); user.setLoginId("booking-browser");
                user.setPassword(new BCryptPasswordEncoder().encode("Booking-test-2026!")); user.setBirthDate(LocalDate.of(1990,1,1)); user.setAddress("서울"); em.persist(user);
                var movie = new Movie(); movie.setTmdbMovieId(9900001L); movie.setTitle("미드나잇 시네마 · 격리 검증"); movie.setRating("ALL"); movie.setRunningTime(120); em.persist(movie);
                for (int i=1;i<=3;i++) {
                    var theater = new Theater(); theater.setName("[격리 검증] 시네마 "+i); theater.setAddress("서울 테스트 주소"); theater.setKakaoPlaceId("browser-"+i); theater.setBrand(TheaterBrand.CGV); em.persist(theater);
                    var preference = new UserPreferredTheater(); preference.setUser(user); preference.setTheater(theater); preference.setPriority(i); em.persist(preference);
                    if (i!=1 && !run.equals("stage7") && !waitingRun) continue;
                    var screen = new Screen(); screen.setName("PREMIUM 1관"); screen.setTheater(theater); em.persist(screen);
                    var show = new Showtime(); show.setMovie(movie); show.setScreen(screen); show.setStartTime(now.plusHours(2)); show.setEndTime(now.plusHours(4));
                    show.setPricePerPerson(10000); show.setTotalSeats(72); show.setAvailableSeats(70); show.setCreatedAt(now); show.setUpdatedAt(now); em.persist(show);
                    browserShows.add(show.getId());
                    for(int row=0;row<6;row++) for(int number=1;number<=12;number++) {
                        var seat = new Seat(); seat.setScreen(screen); seat.setSeatRow(String.valueOf((char)('A'+row))); seat.setSeatNumber(number); seat.setSeatPosition(SeatPosition.MIDDLE_MIDDLE);
                        int segment = number<=3?0:number<=9?1:2; seat.setAdjacencySegment("block-"+segment); seat.setPositionInSegment(number-(segment==0?0:segment==1?3:9)); em.persist(seat);
                        var inventory = new ShowtimeSeat(); inventory.setShowtime(show); inventory.setSeat(seat);
                        if(waitingRun || row==2&&number<=2 || i==2 || i==3 && number%2==0) inventory.setStatus(SeatStatus.BLOCKED); em.persist(inventory);
                    }
                    System.out.println("BROWSER_PATH=/theaters?theater="+theater.getId()+"&movie="+movie.getId()+"&showtime="+show.getId()+"&date="+show.getStartTime().toLocalDate()+"&entry=THEATER_NORMAL");
                    if ((run.equals("stage7") || waitingRun) && i==1) {
                        var from = show.getStartTime().minusMinutes(30).withMinute(0).withSecond(0).withNano(0);
                        System.out.println("BROWSER_SMART_MOVIE_PATH=/movies?movie="+movie.getId()+"&date="+from.toLocalDate()+"&from="+from.toLocalTime()+"&until="+from.plusHours(3).toLocalTime()+"&party=2&entry=MOVIE_SMART");
                    }
                }
                var seatPreference = new UserPreferredSeat(); seatPreference.setUser(user); seatPreference.setPriority(1); seatPreference.setSeatPosition(SeatPosition.MIDDLE_MIDDLE); em.persist(seatPreference);
                em.getTransaction().commit();
            }
            List<String> properties = new ArrayList<>(List.of("--server.port=8081", "--spring.profiles.active=test", "--tmdb.auto-import=false", "--kakao.catalog.auto-import=false",
                    "--booking.seed.enabled=false", "--showtime.seed.enabled=false", "--booking.mock-payment.allow-failure=true", "--spring.jpa.show-sql=false", "--spring.jpa.properties.hibernate.format_sql=false",
                    "--spring.datasource.url="+db.jdbcUrl(), "--spring.datasource.username="+System.getenv("BOOKING_TEST_MYSQL_USER"),
                    "--spring.datasource.password="+System.getenv("BOOKING_TEST_MYSQL_PASSWORD"), "--JWT_SECRET=browser-test-only-not-a-production-key-2026-123456", "--TMDB_ACCESS_TOKEN=test", "--ADMIN_KEY=test"));
            for (String provider : List.of("GOOGLE","NAVER","KAKAO")) { properties.add("--"+provider+"_CLIENT_ID=test"); properties.add("--"+provider+"_CLIENT_SECRET=test"); }
            boolean restartRequested;
            do {
            restartRequested = false;
            try (var context = SpringApplication.run(SmartTicketingApplication.class, properties.toArray(String[]::new))) {
                System.out.println("BOOKING_BROWSER_READY");
                long deadline = System.nanoTime()+Duration.ofMinutes(35).toNanos();
                var released = new HashSet<Integer>();
                while(!Files.exists(stop) && !Files.exists(restart) && System.nanoTime()<deadline) {
                    // Explicit test-only inventory release, limited to this harness's owned UUID DB.
                    if (waitingRun) for (int i=0;i<browserShows.size();i++) {
                        if (!released.contains(i) && Files.exists(Path.of(".gradle/"+run+"-release-"+(i+1)))) {
                            try (var em=db.open()) {
                                em.getTransaction().begin();
                                em.find(Showtime.class,browserShows.get(i),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                                em.createQuery("select i from ShowtimeSeat i where i.showtime.id=:s order by i.id",ShowtimeSeat.class)
                                        .setParameter("s",browserShows.get(i)).setMaxResults(2).getResultList().forEach(seat -> {
                                            if(seat.getStatus()==SeatStatus.BLOCKED) seat.setStatus(SeatStatus.AVAILABLE);
                                        });
                                em.getTransaction().commit(); released.add(i);
                            }
                        }
                    }
                    Thread.sleep(500);
                }
                if (Files.exists(restart)) { Files.delete(restart); restartRequested = true; }
            }
            } while (restartRequested && !Files.exists(stop));
        }
        System.out.println("BOOKING_BROWSER_CLEANED");
    }
}
