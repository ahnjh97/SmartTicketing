package smartticketing.entity;

import smartticketing.entity.enums.BookingEntryPoint;
import smartticketing.entity.enums.BookingGroupStatus;
import smartticketing.entity.enums.SeatPosition;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** 한 번의 관람 의도. 여러 회차 대기가 인원과 신청 당시 선호조건을 공유한다. */
@Entity
@Table(name = "booking_request_groups", indexes = {
        @Index(name = "idx_booking_groups_user_created", columnList = "user_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class BookingRequestGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_kind", length = 20, updatable = false)
    private String candidateKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_zone", length = 30, updatable = false, columnDefinition = "varchar(30)")
    private SeatPosition candidateZone;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private Users user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false, updatable = false)
    private Movie movie;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_point", nullable = false, length = 30, updatable = false, columnDefinition = "varchar(30)")
    private BookingEntryPoint entryPoint;

    @NotNull
    @Column(name = "viewing_date", nullable = false, updatable = false)
    private LocalDate viewingDate;

    @Column(name = "start_time_from", updatable = false)
    private LocalTime startTimeFrom;

    @Column(name = "start_time_to", updatable = false)
    private LocalTime startTimeTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_showtime_id", updatable = false)
    private Showtime selectedShowtime;

    @NotNull
    @Min(1)
    @Column(name = "party_size", nullable = false, updatable = false)
    private Integer partySize;

    // 이전 그룹은 null을 유지한다. 새로운 그룹 API가 검증된 관객 선언을 저장한다.
    @Column(name = "adult_count", updatable = false)
    private Integer adultCount;
    @Column(name = "youth_count", updatable = false)
    private Integer youthCount;
    @Column(name = "companions_eligible", updatable = false)
    private Boolean companionsEligible;
    @Column(name = "guardian_accompanying", updatable = false)
    private Boolean guardianAccompanying;
    @Column(name = "rating_snapshot", length = 20, updatable = false)
    private String ratingSnapshot;

    // 순서를 포함해 복사한다. 회원 선호정보의 이후 수정과 독립적인 값이다.
    @ElementCollection
    @CollectionTable(name = "booking_group_seat_preferences", joinColumns = @JoinColumn(name = "group_id"))
    @OrderColumn(name = "preference_order")
    @Enumerated(EnumType.STRING)
    @Column(name = "seat_position", nullable = false, length = 30, columnDefinition = "varchar(30)")
    private List<SeatPosition> seatPreferences = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "booking_group_theater_preferences",
            joinColumns = @JoinColumn(name = "group_id"),
            inverseJoinColumns = @JoinColumn(name = "theater_id"))
    @OrderColumn(name = "preference_order")
    private List<Theater> theaterPreferences = new ArrayList<>();

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private BookingGroupStatus status = BookingGroupStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
