package smartticketing.admission;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import smartticketing.booking.TemporaryMysqlDatabase;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "tmdb.auto-import=false", "kakao.catalog.auto-import=false", "booking.seed.enabled=false",
        "showtime.seed.enabled=false", "booking.expiry.enabled=false", "booking.waiting.enabled=false",
        "app.admission.enabled=true", "app.admission.capacity=1", "app.admission.secure-cookie=false",
        "JWT_SECRET=isolated-http-test-secret-12345678901234567890", "TMDB_ACCESS_TOKEN=test", "ADMIN_KEY=test"})
class AdmissionHttpTests {
    static TemporaryMysqlDatabase db;
    @LocalServerPort int port;
    @DynamicPropertySource static void config(DynamicPropertyRegistry props) throws Exception {
        db = new TemporaryMysqlDatabase();
        props.add("spring.datasource.url", db::jdbcUrl);
        props.add("spring.datasource.username", () -> System.getenv("BOOKING_TEST_MYSQL_USER"));
        props.add("spring.datasource.password", () -> System.getenv("BOOKING_TEST_MYSQL_PASSWORD"));
        props.add("app.admission.namespace", () -> "admission-http-"+UUID.randomUUID());
    }
    @AfterAll static void close() throws Exception { if (db != null) db.close(); }
    HttpResponse<String> call(HttpClient client, String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
                .timeout(Duration.ofSeconds(5)).method(method,HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void actualServerChecksAdmissionBeforeAuthenticationAndKeepsAuthAfterEntry() throws Exception {
        var cookies = new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build(); var anonymous = HttpClient.newHttpClient()) {
            assertThat(call(client,"GET","/api/movies").statusCode()).isEqualTo(429);
            assertThat(call(client,"GET","/api/health/readiness").statusCode()).isEqualTo(200);
            var entry = call(client,"POST","/api/admission/enter");
            assertThat(entry.statusCode()).isEqualTo(200);
            assertThat(entry.body()).containsAnyOf("WAITING","ADMITTED");
            long deadline = System.nanoTime()+Duration.ofSeconds(8).toNanos();
            String state;
            do { Thread.sleep(100); state = call(client,"GET","/api/admission/status").body(); }
            while (!state.contains("ADMITTED") && System.nanoTime()<deadline);
            assertThat(state).contains("ADMITTED");
            assertThat(call(client,"GET","/api/movies").statusCode()).isEqualTo(200);
            assertThat(call(client,"GET","/api/tickets").statusCode()).isEqualTo(401);
            assertThat(call(anonymous,"GET","/api/movies").statusCode()).isEqualTo(429);
            call(client,"POST","/api/admission/leave");
            assertThat(call(client,"GET","/api/movies").statusCode()).isEqualTo(429);
        }
    }
}
