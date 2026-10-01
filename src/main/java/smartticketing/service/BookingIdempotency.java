package smartticketing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import smartticketing.dto.booking.BookingResult;
import smartticketing.entity.BookingOperation;
import smartticketing.entity.enums.*;
import smartticketing.exception.ApiError;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

@Component
public class BookingIdempotency {
    private final EntityManager em;
    private final JsonMapper json = JsonMapper.builder().build();

    public BookingIdempotency(EntityManager em) { this.em = em; }

    public static String key(String key) {
        // 대문자/공백을 소문자로 합치지 않는다. 계약의 canonical 형식만 허용한다.
        if (key == null || !key.matches("[a-z0-9-]{16,64}"))
            throw new IllegalArgumentException("Idempotency-Key는 소문자 영숫자/하이픈 16~64자여야 합니다.");
        return key;
    }

    public BookingResult execute(Long userId, BookingOperationType type, String key, Object canonical,
                                 LocalDateTime now, Supplier<Object> action) {
        key(key);
        String hash = hash(json.writeValueAsString(canonical));
        // UNIQUE key 획득을 같은 트랜잭션에서 기다린다. PROCESSING 단독 커밋은 없다.
        // 요청 기록 잠금 다음의 도메인 잠금은 반드시 그룹 -> 회차 -> 예약/좌석 순서다.
        em.createNativeQuery("""
                insert into booking_operations
                (user_id, operation_type, request_key, request_hash, status, created_at, updated_at)
                values (:user, :type, :key, :hash, 'PROCESSING', :now, :now)
                on duplicate key update id = id
                """, Object.class).setParameter("user", userId).setParameter("type", type.name())
                .setParameter("key", key).setParameter("hash", hash).setParameter("now", now).executeUpdate();
        var operation = em.createQuery("""
                select o from BookingOperation o where o.user.id=:user
                and o.operationType=:type and o.requestKey=:key
                """, BookingOperation.class).setParameter("user", userId).setParameter("type", type)
                .setParameter("key", key).setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        if (!operation.getRequestHash().equals(hash))
            return error(409, "같은 Idempotency-Key에 다른 요청을 사용할 수 없습니다.", now);
        if (operation.getStatus() != BookingOperationStatus.PROCESSING)
            return new BookingResult(operation.getResponseStatus(), operation.getResponseBody());

        BookingResult result;
        try {
            result = new BookingResult(201, json.writeValueAsString(action.get()));
        } catch (BookingRejection rejected) {
            result = error(rejected.status, rejected.getMessage(), now);
        }
        operation.setStatus(result.status() < 400 ? BookingOperationStatus.COMPLETED : BookingOperationStatus.FAILED);
        operation.setResponseStatus(result.status()); operation.setResponseBody(result.body());
        operation.setUpdatedAt(now);
        em.flush(); // 모든 도메인 변경과 응답 기록은 함께 커밋/롤백된다.
        return result;
    }

    private BookingResult error(int status, String message, LocalDateTime now) {
        return new BookingResult(status, json.writeValueAsString(new ApiError(now, status, message)));
    }

    private static String hash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
