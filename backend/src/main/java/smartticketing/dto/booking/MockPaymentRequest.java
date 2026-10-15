package smartticketing.dto.booking;

import jakarta.validation.constraints.NotNull;
import smartticketing.entity.enums.PaymentMethod;

public record MockPaymentRequest(@NotNull PaymentMethod paymentMethod, Boolean simulateFailure) {}
