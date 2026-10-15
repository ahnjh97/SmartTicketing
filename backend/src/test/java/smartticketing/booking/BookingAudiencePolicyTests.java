package smartticketing.booking;

import org.junit.jupiter.api.Test;
import smartticketing.dto.booking.AudienceRequest;
import smartticketing.entity.Users;
import smartticketing.service.BookingAudiencePolicy;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class BookingAudiencePolicyTests {
    private final LocalDate date = LocalDate.of(2026, 10, 1);
    private Users user(LocalDate birth) { var u = new Users(); u.setBirthDate(birth); return u; }

    @Test void memberBirthDateControlsTwelveAndFifteenRatingsWithoutCheckboxes() {
        for (String rating : new String[]{"12", "15"}) {
            var child = user(date.minusYears(Integer.parseInt(rating)).plusDays(1));
            assertThatThrownBy(() -> BookingAudiencePolicy.validate(child, rating, date, 2,
                    new AudienceRequest(0, 2, null, null))).hasMessageContaining("관람 가능 연령");
            assertThatThrownBy(() -> BookingAudiencePolicy.validate(child, rating, date, 2,
                    new AudienceRequest(1, 1, true, true))).hasMessageContaining("성인 인원");
            assertThatCode(() -> BookingAudiencePolicy.validate(user(date.minusYears(Integer.parseInt(rating))), rating, date, 1,
                    new AudienceRequest(0, 1, null, null))).doesNotThrowAnyException();
        }
    }

    @Test void nineteenRuleUsesJanuaryFirstOfBirthYearAndHasNoGuardianException() {
        assertThatCode(() -> BookingAudiencePolicy.validate(user(LocalDate.of(2007, 12, 31)), "19", date, 1,
                new AudienceRequest(1, 0, true, false))).doesNotThrowAnyException();
        assertThatThrownBy(() -> BookingAudiencePolicy.validate(user(LocalDate.of(2008, 1, 1)), "19", date, 2,
                new AudienceRequest(0, 2, null, null))).hasMessageContaining("보호자 동반으로도");
        assertThatThrownBy(() -> BookingAudiencePolicy.validate(user(LocalDate.of(1990, 1, 1)), "19", date, 2,
                new AudienceRequest(1, 1, true, false))).hasMessageContaining("관람할 수 없습니다");
    }

    @Test void unknownBirthRatingAndInvalidCountsAreRejectedButConsentIsNotRequired() {
        assertThatThrownBy(() -> BookingAudiencePolicy.validate(user(null), "ALL", date, 1,
                new AudienceRequest(1, 0, true, false))).hasMessageContaining("생년월일");
        assertThatThrownBy(() -> BookingAudiencePolicy.rating(null)).hasMessageContaining("관람등급");
        assertThatThrownBy(() -> BookingAudiencePolicy.rating("PG-13")).hasMessageContaining("지원하지");
        assertThatThrownBy(() -> BookingAudiencePolicy.rating("제한상영가")).hasMessageContaining("지원하지");
        assertThatThrownBy(() -> BookingAudiencePolicy.validate(user(LocalDate.of(1990, 1, 1)), "ALL", date, 2,
                new AudienceRequest(1, 0, true, false))).hasMessageContaining("합");
        assertThatCode(() -> BookingAudiencePolicy.validate(user(LocalDate.of(1990, 1, 1)), "ALL", date, 1,
                new AudienceRequest(1, 0, null, null))).doesNotThrowAnyException();
        assertThatThrownBy(() -> BookingAudiencePolicy.validate(user(LocalDate.of(2010, 1, 1)), "ALL", date, 2,
                new AudienceRequest(1, 1, null, null))).hasMessageContaining("성인 인원");
        assertThat(BookingAudiencePolicy.rating("18")).isEqualTo("19");
        assertThat(BookingAudiencePolicy.rating("15세 이상 관람가")).isEqualTo("15");
    }
}
