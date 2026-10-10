package smartticketing.dto.booking;

public record TossOrderResponse(String clientKey, String orderId, String orderName, Integer amount) {}
