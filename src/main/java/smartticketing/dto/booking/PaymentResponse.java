package smartticketing.dto.booking;

import smartticketing.entity.enums.PaymentStatus;
import smartticketing.dto.ticket.TicketResponse;

public record PaymentResponse(Long paymentId, PaymentStatus status, Integer amount,
                              ReservationResponse reservation, TicketResponse ticket) {}
