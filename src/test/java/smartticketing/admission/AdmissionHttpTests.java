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

@Tag("core")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "tmdb.auto-import=false", "kakao.catalog.auto-import=false", "booking.seed.enabled=false",
        "showtime.seed.enabled=false", "booking.expiry.enabled=false", "booking.waiting.enabled=false",
        "app.admission.enabled=true", "app.admission.capacity=1", "app.admission.secure-cookie=false",
        "JWT_SECRET=isolated-http-test-secret-12345678901234567890", "TMDB_ACCESS_TOKEN=test", "ADMIN_KEY=test"})
class AdmissionHttpTests {
    @Test void encodedPublicApiPathsCannotBypassAdmission() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            for(String path:java.util.List.of("/%61pi/movies","/api/%6dovies","/%61pi/auth/check-login-id?loginId=probe",
                    "/other/../api/movies","/api/./movies","//api/movies","/api%2fmovies")) {
                var response=call(client,"GET",path);
                assertThat(response.statusCode()).as("unauthorized entry via %s: %s",path,response.body()).isIn(400,429);
            }
        }
    }
    static TemporaryMysqlDatabase db;
    static final String namespace="admission-http-"+UUID.randomUUID();
    @LocalServerPort int port;
    @DynamicPropertySource static void config(DynamicPropertyRegistry props) throws Exception {
        db = new TemporaryMysqlDatabase();
        props.add("spring.datasource.url", db::jdbcUrl);
        props.add("spring.datasource.username", () -> System.getenv("BOOKING_TEST_MYSQL_USER"));
        props.add("spring.datasource.password", () -> System.getenv("BOOKING_TEST_MYSQL_PASSWORD"));
        props.add("app.admission.namespace", () -> namespace);
    }
    @AfterAll static void close() throws Exception {
        var connection=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("127.0.0.1",6379);
        connection.afterPropertiesSet();
        try {
            var redis=new org.springframework.data.redis.core.StringRedisTemplate(connection);
            var keys=redis.keys("{"+namespace+"}:*");
            if(keys!=null && !keys.isEmpty()) redis.delete(keys);
        } finally { connection.destroy();if(db!=null) db.close(); }
    }
    HttpResponse<String> call(HttpClient client, String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
                .timeout(Duration.ofSeconds(5)).method(method,HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void waitingBrowserCannotCallApiUntilPreviousBrowserLeavesAndItsTurnArrives() throws Exception {
        try(var first=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
            var second=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
            var third=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build()) {
            try {
                call(first,"POST","/api/admission/enter");awaitAdmission(first);
                assertThat(call(second,"POST","/api/admission/enter").body()).contains("WAITING");
                assertThat(call(third,"POST","/api/admission/enter").body()).contains("WAITING","\"ahead\":1");
                assertThat(call(second,"GET","/api/movies").statusCode()).isEqualTo(429);
                assertThat(call(second,"POST","/api/admission/enter").body()).contains("\"ahead\":0");
                call(first,"POST","/api/admission/leave");awaitAdmission(second);
                assertThat(call(first,"GET","/api/movies").statusCode()).isEqualTo(429);
                assertThat(call(second,"GET","/api/movies").statusCode()).isEqualTo(200);
                assertThat(call(third,"GET","/api/admission/status").body()).contains("WAITING");
            } finally {
                call(first,"POST","/api/admission/leave");call(second,"POST","/api/admission/leave");call(third,"POST","/api/admission/leave");
            }
        }
    }
    void awaitAdmission(HttpClient client) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        do {
            if(call(client,"GET","/api/admission/status").body().contains("ADMITTED")) return;
            Thread.sleep(100);
        } while(System.nanoTime()<deadline);
        fail("Browser was not admitted within eight seconds");
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
