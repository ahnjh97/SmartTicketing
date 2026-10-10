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
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "토스 결제 승인이 거절되었습니다. 테스트 결제 상태를 확인하고 다시 시도해주세요.");
            }
        }
        return bookings.payTossConfirmed(userId, reservationId, idempotencyKey,
                request.orderId(), request.paymentKey(), request.amount());
    }
}
