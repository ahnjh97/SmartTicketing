package smartticketing.service;

import jakarta.persistence.*;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.dto.booking.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import static smartticketing.service.BookingHoldService.reject;

@Service
@Transactional
public class BookingGroupService {
    private final EntityManager em;
    private final BookingHoldService holds;
    private final BookingIdempotency operations;
    private final Validator validator;

    public BookingGroupService(EntityManager em, BookingHoldService holds, BookingIdempotency operations, Validator validator) {
        this.em = em; this.holds = holds; this.operations = operations; this.validator = validator;
    }

    public BookingResult create(Long userId, String key, CreateBookingGroupRequest request) {
        BookingIdempotency.key(key);
        var user = holds.requireUser(userId);
        if (request == null || !validator.validate(request).isEmpty()) throw new IllegalArgumentException("관람 요청 입력을 확인해주세요.");
        return operations.execute(userId, BookingOperationType.CREATE_GROUP, key, request, holds.now(), () -> {
            var now = holds.now();
            if (request.viewingDate().isBefore(now.toLocalDate()) || request.viewingDate().getYear() > 9998)
                reject(400, "지난 날짜 또는 지원하지 않는 날짜입니다.");
            if (request.startTimeFrom() != null && (request.startTimeFrom().equals(request.startTimeTo())
                    || request.startTimeFrom().getSecond() != 0 || request.startTimeTo().getSecond() != 0
                    || request.startTimeFrom().getNano() != 0 || request.startTimeTo().getNano() != 0))
                reject(400, "시간 범위는 서로 다른 HH:mm 형식이어야 합니다.");
            var movie = em.find(Movie.class, request.movieId());
            if (movie == null || !movie.isActive()) reject(404, "영화를 찾을 수 없습니다.");
            var group = new BookingRequestGroup(); group.setUser(user); group.setMovie(movie);
            group.setEntryPoint(request.entryPoint()); group.setViewingDate(request.viewingDate()); group.setPartySize(request.partySize());
            group.setStartTimeFrom(request.startTimeFrom()); group.setStartTimeTo(request.startTimeTo());
            if (request.selectedShowtimeId() != null) {
                var show = em.find(Showtime.class, request.selectedShowtimeId(), LockModeType.PESSIMISTIC_WRITE);
                BookingHoldService.validateShow(show, holds.now()); group.setSelectedShowtime(show);
                BookingHoldService.validateGroupShow(group, show);
            }
            String rating = BookingAudiencePolicy.rating(movie.getRating());
            BookingAudiencePolicy.validate(user, rating, request.viewingDate(), request.partySize(), request.audience());
            group.setRatingSnapshot(rating); group.setAdultCount(request.audience().adultCount()); group.setYouthCount(request.audience().youthCount());
            group.setCompanionsEligible(false); group.setGuardianAccompanying(false);
            group.setCreatedAt(now); group.setUpdatedAt(now);
            // 기존 우선순위와 중복 선호 좌석을 그대로 복사한다. 생성 이후 변경 API는 없다.
            group.getTheaterPreferences().addAll(em.createQuery("select p.theater from UserPreferredTheater p where p.user.id=:id order by p.priority, p.id", Theater.class)
                    .setParameter("id", userId).getResultList());
            group.getSeatPreferences().addAll(em.createQuery("select p.seatPosition from UserPreferredSeat p where p.user.id=:id order by p.priority, p.id", SeatPosition.class)
                    .setParameter("id", userId).getResultList());
            em.persist(group);
            return holds.groupResponse(group);
        });
    }
}
