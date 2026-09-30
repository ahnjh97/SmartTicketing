package smartticketing.booking;

import smartticketing.dto.booking.CreateBookingGroupRequest;
import smartticketing.entity.enums.BookingEntryPoint;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class BookingContractValidationTests {
    private static ValidatorFactory factory;
    private static Validator validator;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final LocalTime FROM = LocalTime.of(14, 0);
    private static final LocalTime TO = LocalTime.of(18, 0);

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() { factory.close(); }

    @Test
    void movieSmartNeedsRangeWithoutAnAlreadySelectedShowtime() {
        assertThat(validator.validate(new CreateBookingGroupRequest(
                BookingEntryPoint.MOVIE_SMART, 1L, DATE, 2, FROM, TO, null))).isEmpty();
        assertThat(validator.validate(new CreateBookingGroupRequest(
                BookingEntryPoint.MOVIE_SMART, 1L, DATE, 2, FROM, TO, 9L))).isNotEmpty();
        assertThat(validator.validate(new CreateBookingGroupRequest(
                BookingEntryPoint.MOVIE_SMART, 1L, DATE, 2, null, TO, null))).isNotEmpty();
    }

    @Test
    void bothTheaterFlowsRequireAParticularShowtimeAndNoRange() {
        for (var source : new BookingEntryPoint[]{BookingEntryPoint.THEATER_SMART, BookingEntryPoint.THEATER_NORMAL}) {
            assertThat(validator.validate(new CreateBookingGroupRequest(
                    source, 1L, DATE, 2, null, null, 9L))).isEmpty();
            assertThat(validator.validate(new CreateBookingGroupRequest(
                    source, 1L, DATE, 2, null, null, null))).isNotEmpty();
            assertThat(validator.validate(new CreateBookingGroupRequest(
                    source, 1L, DATE, 2, FROM, TO, 9L))).isNotEmpty();
        }
    }

    @Test
    void groupCannotBeCreatedBeforePartySizeOrWithInvalidIdentifiers() {
        assertThat(validator.validate(new CreateBookingGroupRequest(
                BookingEntryPoint.THEATER_NORMAL, 1L, DATE, null, null, null, 9L))).isNotEmpty();
        assertThat(validator.validate(new CreateBookingGroupRequest(
                BookingEntryPoint.THEATER_NORMAL, 1L, DATE, 0, null, null, 9L))).isNotEmpty();
        assertThat(validator.validate(new CreateBookingGroupRequest(
                BookingEntryPoint.THEATER_NORMAL, -1L, DATE, 2, null, null, 0L))).isNotEmpty();
        assertThat(validator.validate(new CreateBookingGroupRequest(
                null, 1L, null, 2, null, null, 9L))).isNotEmpty();
    }
}
