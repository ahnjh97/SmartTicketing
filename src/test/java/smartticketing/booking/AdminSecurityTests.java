package smartticketing.booking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import smartticketing.auth.*;
import smartticketing.config.SecurityConfig;
import smartticketing.controller.*;
import smartticketing.entity.Users;
import smartticketing.entity.enums.UserStatus;
import smartticketing.repository.UsersRepository;
import smartticketing.service.*;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminSecurityTests {
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class WebConfig {}

    @Test void onlyActiveAdminCanAccessAllManagementEndpointsInProduction() {
        var users = mock(UsersRepository.class);
        var admin = new Users(); admin.setId(1L); admin.setLoginId("admin");
        var ordinary = new Users(); ordinary.setId(2L); ordinary.setLoginId("member");
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(users.findById(2L)).thenReturn(Optional.of(ordinary));
        new WebApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=prod")
                .withUserConfiguration(WebConfig.class, SecurityConfig.class, AdminDataController.class,
                        AdminMovieController.class, AdminTheaterController.class, AdminBookingController.class)
                .withBean(AdminAccess.class, () -> new AdminAccess(users, "admin"))
                .withBean(CustomOAuth2UserService.class, () -> mock(CustomOAuth2UserService.class))
                .withBean(OAuth2SuccessHandler.class, () -> mock(OAuth2SuccessHandler.class))
                .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                .withBean(AdminBookingService.class, () -> mock(AdminBookingService.class))
                .withBean(AdminDataService.class, () -> mock(AdminDataService.class))
                .withBean(AdminTaskService.class, () -> mock(AdminTaskService.class))
                .withBean(ShowtimeScheduleSeedService.class, () -> mock(ShowtimeScheduleSeedService.class))
                .withBean(ShowtimeInventoryService.class, () -> mock(ShowtimeInventoryService.class))
                .withBean(MovieImportService.class, () -> mock(MovieImportService.class))
                .withBean(SeoulTheaterCollectionService.class, () -> mock(SeoulTheaterCollectionService.class))
                .run(context -> {
                    var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                    for (String path : new String[]{"/api/admin/data/collection-status", "/api/admin/data/task", "/api/admin/data/summary", "/api/admin/data/movies", "/api/admin/data/showtimes/1/seats"}) {
                        mvc.perform(get(path)).andExpect(status().isUnauthorized());
                        mvc.perform(get(path).with(jwt().jwt(j -> j.subject("2").claim("admin", true))))
                                .andExpect(status().isForbidden());
                    }
                    for (String path : new String[]{"/api/admin/bookings/preview", "/api/admin/bookings/execute", "/api/admin/data/delete", "/api/admin/data/preview-delete", "/api/admin/data/prepare-schedule",
                            "/api/admin/data/collect-movies", "/api/admin/data/collect-theaters", "/api/admin/movies/import", "/api/admin/theaters/collect-seoul"}) {
                        mvc.perform(post(path)).andExpect(status().isUnauthorized());
                        mvc.perform(post(path).with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isForbidden());
                    }
                    mvc.perform(patch("/api/admin/data/movies/1").with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isForbidden());
                    verifyNoInteractions(context.getBean(AdminDataService.class), context.getBean(MovieImportService.class), context.getBean(SeoulTheaterCollectionService.class));
                    mvc.perform(get("/api/admin/data/summary").with(jwt().jwt(j -> j.subject("1")))).andExpect(status().isOk());
                    mvc.perform(post("/api/admin/data/collect-movies").with(jwt().jwt(j -> j.subject("1")))).andExpect(status().isOk());
                    mvc.perform(post("/api/admin/movies/import").with(jwt().jwt(j -> j.subject("1")))).andExpect(status().isOk());
                    mvc.perform(post("/api/admin/theaters/collect-seoul").with(jwt().jwt(j -> j.subject("1")))).andExpect(status().isOk());
                    admin.setStatus(UserStatus.WITHDRAWN);
                    mvc.perform(get("/api/admin/data/summary").with(jwt().jwt(j -> j.subject("1")))).andExpect(status().isForbidden());
                    mvc.perform(get("/api/admin/data/summary").with(jwt().jwt(j -> j.subject("missing")))).andExpect(status().isForbidden());
                });
    }
}
