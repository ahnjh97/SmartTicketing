package smartticketing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import smartticketing.dto.booking.TossConfirmRequest;
import smartticketing.dto.booking.TossOrderResponse;
import smartticketing.dto.booking.BookingResult;

import java.util.Map;
import java.util.Objects;

@Service
public class TossPaymentService {
    private final BookingPaymentService bookings;
    private final String secretKey;
    private final RestClient client = RestClient.builder()
            .baseUrl("https://api.tosspayments.com")
            .build();

    public TossPaymentService(BookingPaymentService bookings,
            @Value("${toss.secret-key:}") String secretKey) {
        this.bookings = bookings;
        this.secretKey = secretKey == null ? "" : secretKey.trim();
    }

    public TossOrderResponse createOrder(Long userId, Long reservationId) {
        return bookings.createTossOrder(userId, reservationId);
    }

    /** Refund at Toss first; only then release the local reservation and seats. */
    public BookingResult cancelIfPaidToss(Long userId, Long reservationId, String idempotencyKey) {
        String paymentKey = bookings.tossPaymentKeyForCancellation(userId, reservationId);
        if (paymentKey == null) return null;
        if (secretKey.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "토스 시크릿 키가 백엔드에 설정되지 않았습니다.");
        boolean cancelled = false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.post()
                    .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    .headers(headers -> headers.setBasicAuth(secretKey, ""))
                    .body(Map.of("cancelReason", "SmartTicketing 예매 취소"))
                    .retrieve()
                    .body(Map.class);
            cancelled = response != null && "CANCELED".equals(response.get("status"));
        } catch (RestClientResponseException ignored) {
            // A timeout may happen after Toss processed the refund; query before deciding.
        }
        if (!cancelled) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> status = client.get()
                        .uri("/v1/payments/{paymentKey}", paymentKey)
                        .headers(headers -> headers.setBasicAuth(secretKey, ""))
                        .retrieve()
                        .body(Map.class);
                cancelled = status != null && "CANCELED".equals(status.get("status"));
            } catch (RestClientResponseException ignored) {
                // Do not release seats when the provider state cannot be verified.
            }
        }
        if (!cancelled)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "토스 환불 상태를 확인하지 못했습니다. 좌석은 해제하지 않았습니다.");
        return bookings.cancel(userId, reservationId, idempotencyKey);
    }

    public BookingResult confirm(Long userId, Long reservationId, String idempotencyKey, TossConfirmRequest request) {
        boolean alreadyPaid = bookings.validateTossConfirmation(userId, reservationId,
                request.orderId(), request.amount(), request.paymentKey());
        if (!alreadyPaid) {
            if (secretKey.isBlank())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "토스 시크릿 키가 백엔드에 설정되지 않았습니다.");
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = client.post()
                        .uri("/v1/payments/confirm")
                        .headers(headers -> headers.setBasicAuth(secretKey, ""))
                        .body(Map.of("paymentKey", request.paymentKey(),
                                "orderId", request.orderId(),
                                "amount", request.amount()))
                        .retrieve()
                        .body(Map.class);
                if (response == null
                        || !"DONE".equals(response.get("status"))
                        || !Objects.equals(response.get("orderId"), request.orderId())
                        || !(response.get("totalAmount") instanceof Number total)
                        || total.intValue() != request.amount()
                        || !Objects.equals(response.get("paymentKey"), request.paymentKey())) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "토스 결제 승인 응답을 검증하지 못했습니다.");
                }
            } catch (RestClientResponseException error) {
                // The provider may have approved the payment before a network timeout. Reconcile by orderId.
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> reconciled = client.get()
                            .uri("/v1/payments/orders/{orderId}", request.orderId())
                            .headers(headers -> headers.setBasicAuth(secretKey, ""))
                            .retrieve()
                            .body(Map.class);
                    if (reconciled == null
                            || !"DONE".equals(reconciled.get("status"))
                            || !Objects.equals(reconciled.get("orderId"), request.orderId())
                            || !(reconciled.get("totalAmount") instanceof Number total)
                            || total.intValue() != request.amount()
                            || !Objects.equals(reconciled.get("paymentKey"), request.paymentKey())) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                "토스 결제 승인이 거절되었습니다. 테스트 결제 상태를 확인하고 다시 시도해주세요.");
                    }
                } catch (RestClientResponseException queryError) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "토스 결제 승인 상태를 확인하지 못했습니다. 예약 상태를 새로고침한 뒤 다시 확인해주세요.");
                }
            }
        }
        return bookings.payTossConfirmed(userId, reservationId, idempotencyKey,
                request.orderId(), request.paymentKey(), request.amount());
    }
}
