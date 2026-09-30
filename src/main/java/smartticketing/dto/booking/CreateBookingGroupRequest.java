package smartticketing.dto.booking;

import smartticketing.entity.enums.BookingEntryPoint;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/** 신규 API 계약. 인원 입력을 완료한 뒤에만 그룹을 만든다. 사용자/선호정보는 서버가 채운다. */
public record CreateBookingGroupRequest(
        @NotNull BookingEntryPoint entryPoint,
        @NotNull @Min(1) Long movieId,
        @NotNull LocalDate viewingDate,
        @NotNull @Min(1) Integer partySize,
        LocalTime startTimeFrom,
        LocalTime startTimeTo,
        @Min(1) Long selectedShowtimeId
) {
    @JsonIgnore
    @AssertTrue(message = "영화별은 시간 범위, 극장별은 선택 회차가 필요합니다.")
    public boolean isSelectionShapeValid() {
        if (entryPoint == null) return true; // @NotNull이 보고한다.
        return switch (entryPoint) {
            case MOVIE_SMART -> selectedShowtimeId == null && startTimeFrom != null && startTimeTo != null;
            case THEATER_SMART, THEATER_NORMAL -> selectedShowtimeId != null && startTimeFrom == null && startTimeTo == null;
        };
    }
}
