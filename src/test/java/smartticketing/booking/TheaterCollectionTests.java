package smartticketing.booking;

import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import smartticketing.entity.TheaterCollectionProgress;
import smartticketing.repository.TheaterCollectionProgressRepository;
import smartticketing.service.SeoulTheaterCollectionService;
import smartticketing.service.TheaterCatalogWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TheaterCollectionTests {
    private final Map<String, TheaterCollectionProgress> states = new HashMap<>();
    private final TheaterCollectionProgressRepository progress = mock(TheaterCollectionProgressRepository.class);
    private final TheaterCatalogWriter writer = mock(TheaterCatalogWriter.class);
    private MockRestServiceServer server;
    private RestClient client;
    private SeoulTheaterCollectionService service;
    private static final String EMPTY = "{\"meta\":{\"is_end\":true},\"documents\":[]}";

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://kakao.test");
        server = MockRestServiceServer.bindTo(builder).build(); client = builder.build();
        service = new SeoulTheaterCollectionService(progress, writer, client, "test");
        when(progress.findById(anyString())).thenAnswer(c -> Optional.ofNullable(states.get(c.getArgument(0))));
        when(writer.savePage(anyString(), anyInt(), anyBoolean(), anyList())).thenAnswer(c -> {
            var state = new TheaterCollectionProgress(c.getArgument(0));
            state.setNextPage((int)c.getArgument(1) + 1); state.setComplete(c.getArgument(2));
            states.put(state.getId(), state);
            return new TheaterCatalogWriter.Counts(((List<?>)c.getArgument(3)).size(), 0);
        });
        doAnswer(c -> { states.clear(); return null; }).when(writer).reset(anyList());
    }
    @AfterEach void verifyRequests() { server.verify(); }

    @Test void completedQueriesSurviveServiceRecreationAndMakeZeroCalls() {
        server.expect(times(75), requestTo(org.hamcrest.Matchers.startsWith("https://kakao.test/")))
                .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        assertThat(service.collectSeoulTheaters()).containsEntry("apiCallCount", 75);
        service = new SeoulTheaterCollectionService(progress, writer, client, "test");
        assertThat(service.collectSeoulTheaters()).containsEntry("apiCallCount", 0).containsEntry("skippedQueryCount", 75);
    }

    @Test void failedPageIsRetriedWithoutRepeatingCompletedQueries() {
        server.expect(anything()).andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> service.collectSeoulTheaters()).isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        assertThat(states).hasSize(1);
        server.reset();
        server.expect(times(74), anything()).andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        assertThat(service.collectSeoulTheaters()).containsEntry("apiCallCount", 74).containsEntry("skippedQueryCount", 1);
    }

    @Test void filteredEmptyPageStillFollowsMetaAndBlankRoadAddressFallsBack() {
        server.expect(request -> assertThat(URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8))
                        .contains("query=서울 강남구 CGV", "page=1"))
                .andRespond(withSuccess("{\"meta\":{\"is_end\":false},\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("page=2"))
                .andRespond(withSuccess("""
                {"meta":{"is_end":true},"documents":[
                  {"id":"1","place_name":"CGV 강남","road_address_name":"", "address_name":"서울 강남구 역삼동",
                   "x":"127.02","y":"37.5"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(times(74), anything()).andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        assertThat(service.collectSeoulTheaters()).containsEntry("apiCallCount", 76).containsEntry("insertedCount", 1);
        verify(writer).savePage(eq("seoul-v1:강남구:CGV"), eq(2), eq(true), argThat(p ->
                p.size() == 1 && p.get(0).address().equals("서울 강남구 역삼동")));
    }

    @Test void resumesAtFailedPageWithinQuery() {
        server.expect(anything()).andRespond(withSuccess("{\"meta\":{\"is_end\":false},\"documents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(() -> service.collectSeoulTheaters()).isInstanceOf(RuntimeException.class);
        assertThat(states.get("seoul-v1:강남구:CGV").getNextPage()).isEqualTo(2);
        server.reset();
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("page=2"))
                .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        server.expect(times(74), anything()).andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        assertThat(service.collectSeoulTheaters()).containsEntry("apiCallCount", 75);
    }

    @Test void explicitRefreshRechecksCompletedQueries() {
        server.expect(times(150), anything()).andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        service.collectSeoulTheaters();
        assertThat(service.collectSeoulTheaters(true)).containsEntry("apiCallCount", 75);
        verify(writer).reset(argThat(keys -> keys.size() == 75));
    }

    @Test void malformedResponseNeverMarksCollectionComplete() {
        server.expect(anything()).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.collectSeoulTheaters()).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }

    @Test void missingKeyMakesNoNetworkRequests() {
        service = new SeoulTheaterCollectionService(progress, writer, client, "");
        assertThatThrownBy(() -> service.collectSeoulTheaters()).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(progress, writer);
    }

    @Test void simultaneousStartupAndAdminRequestShareOneCollection() throws Exception {
        server.expect(times(75), anything()).andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.collectSeoulTheaters());
            var b = pool.submit(() -> service.collectSeoulTheaters());
            assertThat((int)a.get().get("apiCallCount") + (int)b.get().get("apiCallCount")).isEqualTo(75);
        }
    }
}
