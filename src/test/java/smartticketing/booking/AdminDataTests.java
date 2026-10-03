package smartticketing.booking;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import smartticketing.service.AdminDataService;
import smartticketing.service.AdminDataService.*;
import smartticketing.controller.AdminDataController;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminDataTests {
    private record Fixture(AdminDataService service, JdbcTemplate sql, TransactionTemplate tx) {
        Preview delete(Scope scope, Preview preview, boolean bookings) {
            return tx.execute(status -> service.delete(new DeleteRequest(scope, preview.fingerprint(), "삭제", bookings)));
        }
    }
    private Fixture fixture(TemporaryMysqlDatabase database) {
        var source = new DriverManagerDataSource(database.jdbcUrl(), System.getenv("BOOKING_TEST_MYSQL_USER"), System.getenv("BOOKING_TEST_MYSQL_PASSWORD"));
        var sql = new JdbcTemplate(source);
        sql.update("insert into users(id,name,nickname,status) values(1,'관리 테스트','테스트','ACTIVE')");
        for (int id = 1; id <= 2; id++) {
            sql.update("insert into movies(id,tmdb_movie_id,title,running_time,is_active,audience_count) values(?,?,?,120,true,0)", id, id, "영화" + id);
            sql.update("insert into theaters(id,name,address,brand,kakao_place_id,is_active) values(?,?,'서울 테스트','CGV',?,true)", id, "영화관" + id, "test" + id);
            sql.update("insert into screens(id,theater_id,name,is_active) values(?,?,?,true)", id, id, "1관");
            sql.update("insert into seats(id,screen_id,seat_row,seat_number,seat_position,adjacency_segment,position_in_segment,is_active) values(?,?,'A',1,'MIDDLE_MIDDLE','A',1,true)", id, id);
            sql.update("""
                    insert into showtimes(id,movie_id,screen_id,start_time,end_time,total_seats,available_seats,price_per_person,status,created_at,updated_at)
                    values(?,?,?,'2026-10-10 12:00:00','2026-10-10 14:00:00',1,0,10000,'SCHEDULED',now(),now())
                    """, id, id, id);
            sql.update("""
                    insert into booking_request_groups(id,user_id,movie_id,selected_showtime_id,entry_point,viewing_date,party_size,status,created_at,updated_at)
                    values(?,1,?,?,'THEATER_NORMAL','2026-10-10',1,'COMPLETED',now(),now())
                    """, id, id, id);
            sql.update("insert into booking_group_seat_preferences(group_id,seat_position,preference_order) values(?,'MIDDLE_MIDDLE',0)", id);
            sql.update("insert into waiting_queues(id,user_id,showtime_id,request_group_id,queue_number,status,created_at,updated_at) values(?,1,?,?,1,'COMPLETED',now(),now())", id, id, id);
            sql.update("""
                    insert into reservations(id,user_id,showtime_id,waiting_queue_id,request_group_id,reservation_type,status,total_amount,created_at,updated_at)
                    values(?,1,?,?,?,'NORMAL','CONFIRMED',10000,now(),now())
                    """, id, id, id, id);
            sql.update("insert into showtime_seats(id,showtime_id,seat_id,reservation_id,status) values(?,?,?,?,'RESERVED')", id, id, id, id);
            sql.update("insert into reservation_seats(reservation_id,seat_id,price) values(?,?,10000)", id, id);
            sql.update("insert into payments(reservation_id,payment_method,status,amount,created_at,updated_at) values(?,'MOCK','READY',10000,now(),now())", id);
            sql.update("insert into tickets(reservation_id,ticket_number,status,created_at,updated_at) values(?,?,'VALID',now(),now())", id, "test" + id);
            sql.update("insert into notifications(user_id,type,message,booking_group_id,reservation_id,is_read,created_at) values(1,'RESERVATION_COMPLETED','테스트',?,?,false,now())", id, id);
            sql.update("insert into queue_counters(showtime_id,next_queue_number) values(?,2)", id);
            sql.update("insert into user_preferred_theaters(user_id,theater_id,priority) values(1,?,?)", id, id);
        }
        sql.update("insert into booking_group_theater_preferences(group_id,theater_id,preference_order) values(1,1,0),(1,2,1),(2,1,0),(2,2,1)");
        return new Fixture(new AdminDataService(source), sql, new TransactionTemplate(new DataSourceTransactionManager(source)));
    }
    private Scope selected(String kind, long id) { return new Scope(kind, "selected", List.of(id), null, null, null, null); }

    @Test void seatCancellationExpandsToWholeReservationAndPreservesHistoryAndOtherShow() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database);
            f.sql.update("insert into seats(id,screen_id,seat_row,seat_number,seat_position,adjacency_segment,position_in_segment,is_active) values(3,1,'A',2,'MIDDLE_MIDDLE','A',2,true)");
            f.sql.update("insert into reservation_seats(reservation_id,seat_id,price) values(1,3,10000)");
            f.sql.update("insert into showtime_seats(id,showtime_id,seat_id,reservation_id,status) values(3,1,3,1,'RESERVED')");
            f.sql.update("update showtimes set total_seats=2 where id=1");
            var service = new smartticketing.service.AdminBookingService(f.sql.getDataSource());
            var scope = new smartticketing.service.AdminBookingService.Scope(1, "selected", List.of(1L), "cancel");
            var preview = service.preview(scope);
            assertThat(preview.reservations()).isEqualTo(1);
            assertThat(preview.seats()).containsExactly("A1", "A2");
            f.tx.execute(status -> service.execute(new smartticketing.service.AdminBookingService.Request(scope, preview.fingerprint(), "취소")));
            assertThat(f.sql.queryForObject("select status from reservations where id=1", String.class)).isEqualTo("CANCELLED");
            assertThat(f.sql.queryForObject("select status from payments where reservation_id=1", String.class)).isEqualTo("CANCELLED");
            assertThat(f.sql.queryForObject("select status from tickets where reservation_id=1", String.class)).isEqualTo("CANCELLED");
            assertThat(f.sql.queryForObject("select count(*) from reservation_seats where reservation_id=1", Long.class)).isEqualTo(2);
            assertThat(f.sql.queryForObject("select available_seats from showtimes where id=1", Long.class)).isEqualTo(2);
            assertThat(f.sql.queryForObject("select status from reservations where id=2", String.class)).isEqualTo("CONFIRMED");
            assertThat(service.preview(scope).reservations()).isZero();
        }
    }

    @Test void purgeRemovesCancelledHistoryAndOrphanGroupButKeepsCatalogAndOtherShow() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database);
            f.sql.update("update reservations set status='CANCELLED' where id=1");
            var service = new smartticketing.service.AdminBookingService(f.sql.getDataSource());
            var scope = new smartticketing.service.AdminBookingService.Scope(1, "all", null, "purge");
            var preview = service.preview(scope);
            f.tx.execute(status -> service.execute(new smartticketing.service.AdminBookingService.Request(scope, preview.fingerprint(), "삭제")));
            for (String table : List.of("reservations", "reservation_seats", "payments", "tickets", "notifications", "waiting_queues", "booking_request_groups"))
                assertThat(f.sql.queryForObject("select count(*) from " + table, Long.class)).as(table).isEqualTo(1);
            for (String table : List.of("movies", "theaters", "showtimes", "seats", "showtime_seats"))
                assertThat(f.sql.queryForObject("select count(*) from " + table, Long.class)).as(table).isEqualTo(2);
            assertThat(f.sql.queryForObject("select available_seats from showtimes where id=1", Long.class)).isEqualTo(1);
            assertThat(f.sql.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
        }
    }

    @Test void bookingPreviewDetectsChangedStateAndRejectsForeignSeat() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database);
            var service = new smartticketing.service.AdminBookingService(f.sql.getDataSource());
            assertThatThrownBy(() -> service.preview(new smartticketing.service.AdminBookingService.Scope(1, "selected", List.of(2L), "purge")))
                    .hasMessageContaining("다른 상영관");
            var scope = new smartticketing.service.AdminBookingService.Scope(1, "all", null, "cancel");
            var preview = service.preview(scope);
            f.sql.update("update payments set status='SUCCESS' where reservation_id=1");
            assertThatThrownBy(() -> f.tx.execute(status -> service.execute(new smartticketing.service.AdminBookingService.Request(scope, preview.fingerprint(), "취소"))))
                    .hasMessageContaining("변경");
            assertThat(f.sql.queryForObject("select status from reservations where id=1", String.class)).isEqualTo("CONFIRMED");
        }
    }

    @Test void purgeKeepsOtherReservationInSharedGroupAndCancelAlsoStopsQueueWithoutReservation() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database);
            f.sql.update("update reservations set request_group_id=1 where id=2");
            f.sql.update("update waiting_queues set request_group_id=1 where id=2");
            var service = new smartticketing.service.AdminBookingService(f.sql.getDataSource());
            var scope = new smartticketing.service.AdminBookingService.Scope(1, "selected", List.of(1L), "purge");
            var preview = service.preview(scope);
            f.tx.execute(status -> service.execute(new smartticketing.service.AdminBookingService.Request(scope, preview.fingerprint(), "삭제")));
            assertThat(f.sql.queryForObject("select status from booking_request_groups where id=1", String.class)).isEqualTo("COMPLETED");
            assertThat(f.sql.queryForObject("select status from reservations where id=2", String.class)).isEqualTo("CONFIRMED");
            assertThat(f.sql.queryForObject("select count(*) from waiting_queues where id=2", Long.class)).isEqualTo(1);
            f.sql.update("insert into waiting_queues(id,user_id,showtime_id,request_group_id,queue_number,status,created_at,updated_at) values(3,1,1,2,3,'WAITING',now(),now())");
            var all = new smartticketing.service.AdminBookingService.Scope(1, "all", null, "cancel");
            var queuePreview = service.preview(all);
            assertThat(queuePreview.reservations()).isZero();
            assertThat(queuePreview.waitingQueues()).isEqualTo(1);
            f.tx.execute(status -> service.execute(new smartticketing.service.AdminBookingService.Request(all, queuePreview.fingerprint(), "취소")));
            assertThat(f.sql.queryForObject("select status from waiting_queues where id=3", String.class)).isEqualTo("CANCELLED");
        }
    }

    @Test void bookingPurgeRollsBackIfUnknownDependentPreventsDeletion() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database);
            f.sql.execute("create table admin_booking_dependency(reservation_id bigint primary key, foreign key(reservation_id) references reservations(id))");
            f.sql.update("insert into admin_booking_dependency values(1)");
            var service = new smartticketing.service.AdminBookingService(f.sql.getDataSource());
            var scope = new smartticketing.service.AdminBookingService.Scope(1, "all", null, "purge");
            var preview = service.preview(scope);
            assertThatThrownBy(() -> f.tx.execute(status -> service.execute(new smartticketing.service.AdminBookingService.Request(scope, preview.fingerprint(), "삭제"))))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(f.sql.queryForObject("select status from showtime_seats where id=1", String.class)).isEqualTo("RESERVED");
            assertThat(f.sql.queryForObject("select count(*) from payments", Long.class)).isEqualTo(2);
        }
    }

    @Test void browseReturnsTheaterButtonsAndOnlyTheirMoviesAndDates() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database);
            var options = f.service.browse(1L, null);
            assertThat((List<?>) options.get("theaters")).hasSize(2);
            assertThat((List<?>) options.get("movies")).hasSize(1);
            assertThat((List<?>) options.get("dates")).hasSize(1);
            assertThat((List<?>) f.service.browse(1L, 2L).get("dates")).isEmpty();
        }
    }

    @Test void selectedMovieDeletesDependentsAndPreservesOtherMovieAndAccount() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database); var scope = selected("movies", 1);
            var preview = f.service.preview(scope);
            assertThat(preview.targetCount()).isEqualTo(1); assertThat(preview.hasBookings()).isTrue();
            assertThat(preview.counts()).containsEntry("payments", 1L).containsEntry("showtime_seats", 1L);
            assertThatThrownBy(() -> f.delete(scope, preview, false)).hasMessageContaining("동의");
            assertThat(f.sql.queryForObject("select count(*) from movies", Long.class)).isEqualTo(2);
            f.delete(scope, preview, true);
            assertThat(f.sql.queryForList("select id from movies", Long.class)).containsExactly(2L);
            for (String table : List.of("showtimes", "showtime_seats", "reservations", "reservation_seats", "payments", "tickets", "waiting_queues", "notifications", "booking_request_groups"))
                assertThat(f.sql.queryForObject("select count(*) from " + table, Long.class)).as(table).isEqualTo(1);
            assertThat(f.sql.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
            assertThat(f.sql.queryForObject("select count(*) from seats", Long.class)).isEqualTo(2);
        }
    }

    @Test void showtimeDeletionRetainsCatalogAndClearsGroupReference() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database); var scope = selected("showtimes", 1);
            f.sql.execute("create table admin_test_dependency(showtime_id bigint primary key, foreign key(showtime_id) references showtimes(id))");
            f.sql.update("insert into admin_test_dependency values(1)");
            var preview = f.service.preview(scope);
            assertThatThrownBy(() -> f.delete(scope, preview, true)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(f.sql.queryForObject("select count(*) from reservations", Long.class)).isEqualTo(2);
            assertThat(f.sql.queryForObject("select selected_showtime_id from booking_request_groups where id=1", Long.class)).isEqualTo(1);
            f.sql.execute("drop table admin_test_dependency");
            f.delete(scope, f.service.preview(scope), true);
            assertThat(f.sql.queryForObject("select count(*) from movies", Long.class)).isEqualTo(2);
            var group = f.sql.queryForMap("select selected_showtime_id,status from booking_request_groups where id=1");
            assertThat(group.get("selected_showtime_id")).isNull(); assertThat(group.get("status")).isEqualTo("CANCELLED");
            assertThat(f.sql.queryForObject("select count(*) from showtimes", Long.class)).isEqualTo(1);
        }
    }

    @Test void theaterDeletionCleansPreferencesAndCompactsSurvivingOrder() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database); var scope = selected("theaters", 1);
            f.delete(scope, f.service.preview(scope), true);
            assertThat(f.sql.queryForList("select id from theaters", Long.class)).containsExactly(2L);
            assertThat(f.sql.queryForObject("select count(*) from seats", Long.class)).isEqualTo(1);
            assertThat(f.sql.queryForList("select preference_order from booking_group_theater_preferences", Integer.class)).containsExactly(0, 0);
            assertThat(f.sql.queryForObject("select count(*) from user_preferred_theaters", Long.class)).isEqualTo(1);
        }
    }

    @Test void filteredAndAllScopesDifferAndChangedPreviewCannotDelete() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var f = fixture(database);
            var filtered = new Scope("movies", "filtered", null, "영화1", null, null, null);
            assertThat(f.service.preview(filtered).targetCount()).isEqualTo(1);
            assertThat(f.service.list(filtered, 0).get("total")).isEqualTo(1L);
            assertThat(f.service.seats(1)).hasSize(1);
            assertThatThrownBy(() -> f.tx.execute(status -> { f.service.edit("showtimes", 1, new Edit(null, null, null, null, null, 20000)); return null; }))
                    .hasMessageContaining("가격을 변경할 수 없습니다");
            var all = new Scope("movies", "all", null, "영화1", null, null, null);
            var before = f.service.preview(all);
            assertThat(before.targetCount()).isEqualTo(2);
            f.sql.update("insert into movies(tmdb_movie_id,title,is_active,audience_count) values(3,'추가 영화',true,0)");
            assertThatThrownBy(() -> f.delete(all, before, true)).hasMessageContaining("변경");
            assertThat(f.sql.queryForObject("select count(*) from movies", Long.class)).isEqualTo(3);
            assertThatThrownBy(() -> f.service.preview(selected("users", 1))).hasMessageContaining("지원하지");
            assertThatThrownBy(() -> f.service.preview(new Scope("movies", "selected", List.of(), null, null, null, null))).hasMessageContaining("선택");
            f.delete(all, f.service.preview(all), true);
            assertThat(f.sql.queryForObject("select count(*) from movies", Long.class)).isZero();
            assertThat(f.sql.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
        }
    }

    @Test void adminControllersExistInEveryEnvironment() {
        for (String[] profiles : List.of(new String[]{}, new String[]{"dev"}, new String[]{"test"}, new String[]{"prod"}, new String[]{"production"}, new String[]{"dev", "prod"}, new String[]{"test", "production"})) {
            try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles(profiles);
                context.registerBean(AdminDataService.class, () -> mock(AdminDataService.class));
                context.registerBean(smartticketing.service.ShowtimeScheduleSeedService.class, () -> mock(smartticketing.service.ShowtimeScheduleSeedService.class));
                context.registerBean(smartticketing.service.ShowtimeInventoryService.class, () -> mock(smartticketing.service.ShowtimeInventoryService.class));
                context.registerBean(smartticketing.service.MovieImportService.class, () -> mock(smartticketing.service.MovieImportService.class));
                context.registerBean(smartticketing.service.SeoulTheaterCollectionService.class, () -> mock(smartticketing.service.SeoulTheaterCollectionService.class));
                context.register(AdminDataController.class, smartticketing.controller.AdminMovieController.class, smartticketing.controller.AdminTheaterController.class);
                context.refresh();
                boolean available = true;
                assertThat(context.getBeansOfType(AdminDataController.class).size()).isEqualTo(available ? 1 : 0);
                assertThat(context.getBeansOfType(smartticketing.controller.AdminMovieController.class).size()).isEqualTo(available ? 1 : 0);
                assertThat(context.getBeansOfType(smartticketing.controller.AdminTheaterController.class).size()).isEqualTo(available ? 1 : 0);
                if (available) {
                    context.getBean(AdminDataController.class).summary();
                    verify(context.getBean(AdminDataService.class)).summary();
                }
            }
        }
    }
}
