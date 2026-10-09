package smartticketing.util;

import java.time.LocalDateTime;

/** Stable descending ordering, including records with identical timestamps. */
public record CreatedCursor(LocalDateTime time, Long id) {
    public static CreatedCursor parse(String value, int size) {
        if (size < 1 || size > 100) throw new IllegalArgumentException("size는 1~100이어야 합니다.");
        if (value == null) return new CreatedCursor(null, null);
        try {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            var cursor = new CreatedCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
            if (cursor.id() < 1) throw new IllegalArgumentException();
            return cursor;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("잘못된 페이지 커서입니다.");
        }
    }
    public static String encode(LocalDateTime time, Long id) { return time + "|" + id; }
}
