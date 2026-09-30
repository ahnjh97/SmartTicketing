package smartticketing.booking;

import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import smartticketing.controller.BookingCatalogController;
import smartticketing.dto.booking.CatalogResponse;
import smartticketing.exception.ApiExceptionHandler;
import smartticketing.service.BookingCatalogService;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingCatalogControllerTests {
    private BookingCatalogService catalog;
    private MockMvc mvc;

    @BeforeEach void setup() {
        catalog = mock(BookingCatalogService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BookingCatalogController(catalog))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void emptyPageRemainsSuccessfulWithPagingMetadata() throws Exception {
        when(catalog.movies(0, 20)).thenReturn(new CatalogResponse.Page<>(List.of(), 0, 20, 0));
        mvc.perform(get("/api/movies")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.size").value(20));
    }

    @Test void missingResourceAndBadParametersUseExistingErrorShape() throws Exception {
        when(catalog.movie(999L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "영화를 찾을 수 없습니다."));
        mvc.perform(get("/api/movies/999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.message").value("영화를 찾을 수 없습니다."));
        mvc.perform(get("/api/movies/not-an-id")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/theaters").param("size", "not-a-number")).andExpect(status().isBadRequest());
    }
}
