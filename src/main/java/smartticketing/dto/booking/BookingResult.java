package smartticketing.dto.booking;

/** 이미 직렬화한 응답을 보관하여 재전송 시 시각을 포함한 본문도 동일하게 반환한다. */
public record BookingResult(int status, String body) {}
