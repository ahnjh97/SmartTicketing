package smartticketing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import smartticketing.booking.TemporaryMysqlDatabase;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {"tmdb.auto-import=false", "kakao.catalog.auto-import=false", "booking.seed.enabled=false",
        "showtime.seed.enabled=false", "booking.expiry.enabled=false", "booking.waiting.enabled=false",
        "JWT_SECRET=isolated-context-test-only-secret-12345678901234567890", "TMDB_ACCESS_TOKEN=test", "ADMIN_KEY=test",
        "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test", "NAVER_CLIENT_ID=test", "NAVER_CLIENT_SECRET=test", "KAKAO_CLIENT_ID=test", "KAKAO_CLIENT_SECRET=test"})
class SmartTicketingApplicationTests {
    private static TemporaryMysqlDatabase database;
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) throws Exception {
        database = new TemporaryMysqlDatabase();
        properties.add("spring.datasource.url", database::jdbcUrl);
        properties.add("spring.datasource.username", () -> System.getenv("BOOKING_TEST_MYSQL_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("BOOKING_TEST_MYSQL_PASSWORD"));
    }
    @AfterAll static void cleanup() throws Exception { if (database != null) database.close(); }

	@Test
	void contextLoads(CapturedOutput output) {
		assertThat(LoggerFactory.getLogger("org.hibernate.SQL").isDebugEnabled()).isFalse();
		assertThat(output.getAll()).contains("[DB 구조] 테이블 총", "새로 생성 0개");
		assertThat(output.getAll()).doesNotContain("Hibernate:");
	}

}
