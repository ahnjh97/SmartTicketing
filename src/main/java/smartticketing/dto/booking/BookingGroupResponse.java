package smartticketing.dto.booking;

import smartticketing.entity.enums.BookingEntryPoint;
import smartticketing.entity.enums.BookingGroupStatus;
import smartticketing.entity.enums.SeatPosition;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** null activeReservationId는 현재 활성 선점이 없음을 뜻한다. 선호 배열은 우선순위 순서다. */
public record BookingGroupResponse(
        Long id, BookingEntryPoint entryPoint, Long movieId, LocalDate viewingDate,
        Integer partySize, LocalTime startTimeFrom, LocalTime startTimeTo,
        Long selectedShowtimeId, List<Long> theaterPreferences, List<SeatPosition> seatPreferences,
        BookingGroupStatus status, Long activeReservationId
) { }
