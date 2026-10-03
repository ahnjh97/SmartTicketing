package smartticketing.dto.ticket;

public record TicketVerifyResponse(
        boolean used,
        String message,
        TicketResponse ticket
) {
}
