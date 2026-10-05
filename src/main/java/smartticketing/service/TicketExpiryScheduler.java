package smartticketing.service;

import smartticketing.entity.enums.TicketStatus;
import smartticketing.repository.TicketRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TicketExpiryScheduler {

    private final TicketRepository tickets;

    public TicketExpiryScheduler(TicketRepository tickets) {
        this.tickets = tickets;
    }

    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    public void expireTickets() {
        LocalDateTime now = LocalDateTime.now();
        tickets.expireValidTickets(
                TicketStatus.VALID,
                TicketStatus.EXPIRED,
                now
        );
    }
}
