package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.time.LocalDate;
import java.util.List;

/** Discovery is read-only. Opening a group uses the existing locked expiry/recovery path. */
@Service
@Transactional(readOnly = true)
public class BookingRecoveryService {
    private final EntityManager em;
    private final BookingHoldService holds;
    public BookingRecoveryService(EntityManager em, BookingHoldService holds) { this.em = em; this.holds = holds; }
    public record Item(Long id, String movieTitle, LocalDate viewingDate, int partySize, BookingGroupStatus status, String path) {}
    public record Page(List<Item> items, boolean hasMore) {}

    public Page list(Long userId, Long before) {
        holds.requireUser(userId);
        if (before != null && before < 1) throw new IllegalArgumentException("잘못된 페이지입니다.");
        var groups = em.createQuery("select g from BookingRequestGroup g join fetch g.movie where g.user.id=:user and g.id < :before order by g.id desc", BookingRequestGroup.class)
                .setParameter("user", userId).setParameter("before", before == null ? Long.MAX_VALUE : before).setMaxResults(21).getResultList();
        return new Page(groups.stream().limit(20).map(this::item).toList(), groups.size() > 20);
    }

    @Transactional
    public Item one(Long userId, Long id) {
        // Lock before the first consistent read: a concurrent commit while we wait
        // must be visible to the latest-reservation lookup under MySQL REPEATABLE_READ.
        var group = holds.ownedGroupForRead(userId, id);
        holds.requireUser(userId);
        holds.expireLockedGroup(group);
        return item(group);
    }

    private Item item(BookingRequestGroup g) {
        var reservations = em.createQuery("select r.id from Reservation r where r.requestGroup.id=:id order by r.id desc", Long.class)
                .setParameter("id", g.getId()).setMaxResults(1).getResultList();
        boolean movie = g.getEntryPoint() == BookingEntryPoint.MOVIE_SMART;
        String path = (movie ? "/movies" : "/theaters") + "?entry=" + g.getEntryPoint() + "&group=" + g.getId()
                + "&movie=" + g.getMovie().getId() + "&date=" + g.getViewingDate() + "&party=" + g.getPartySize();
        if (g.getSelectedShowtime() != null) path += "&showtime=" + g.getSelectedShowtime().getId() + "&theater=" + g.getSelectedShowtime().getScreen().getTheater().getId();
        if (movie) path += "&from=" + g.getStartTimeFrom() + "&until=" + g.getStartTimeTo();
        if (!reservations.isEmpty()) path += "&reservation=" + reservations.getFirst();
        return new Item(g.getId(), g.getMovie().getTitle(), g.getViewingDate(), g.getPartySize(), g.getStatus(), path);
    }
}
