package com.SmartTicketing.SmartTicketing.dto.ticket;

import com.SmartTicketing.SmartTicketing.entity.enums.TicketStatus;

import java.time.LocalDateTime;
import java.util.List;

public record TicketResponse(
        Long ticketId, Long reservationId, String ticketNumber, String qrCode, TicketStatus status,
        String movieTitle, String theaterName, String screenName, LocalDateTime startTime, LocalDateTime endTime,
        List<String> seats, LocalDateTime createdAt
) {
}
