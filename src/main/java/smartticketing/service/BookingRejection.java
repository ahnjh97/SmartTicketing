package smartticketing.service;

/** 쓰기 전 검증에서만 사용한다. DB/직렬화 오류는 이 예외로 바꾸지 않는다. */
final class BookingRejection extends RuntimeException {
    final int status;
    final String code;
    BookingRejection(int status, String message) { this(status, null, message); }
    BookingRejection(int status, String code, String message) { super(message); this.status = status; this.code = code; }
}
