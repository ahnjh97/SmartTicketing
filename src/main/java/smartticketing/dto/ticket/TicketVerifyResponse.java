package smartticketing.dto.ticket;

public record TicketVerifyResponse(
        boolean used,
        boolean processing,
        String message,
        TicketResponse ticket
) {
}
