package smartticketing.booking;

import smartticketing.config.BookingSeedConfiguration;
import smartticketing.service.BookingSeedService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingSeedActivationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(EntityManager.class, () -> mock(EntityManager.class))
            .withUserConfiguration(BookingSeedConfiguration.class, BookingSeedService.class);

    @Test void cannotEnableWithoutDevelopmentOrTestProfile() {
        runner.withPropertyValues("booking.seed.enabled=true").run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context).doesNotHaveBean(BookingSeedService.class);
            assertThat(context).doesNotHaveBean("bookingSeedRunner");
        });
    }

    @Test void developmentProfileAloneDoesNotRunSeeder() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context).doesNotHaveBean(BookingSeedService.class);
        });
    }

    @Test void explicitDevelopmentActivationProvidesSeederAndRunner() {
        runner.withPropertyValues("spring.profiles.active=dev", "booking.seed.enabled=true", "booking.seed.theater-ids=1")
                .run(context -> {
                    assertThat(context).hasNotFailed(); assertThat(context).hasSingleBean(BookingSeedService.class);
                    assertThat(context).hasBean("bookingSeedRunner");
                    assertThat(((org.springframework.core.Ordered) context.getBean("bookingSeedRunner")).getOrder()).isGreaterThan(0);
                });
    }
}
