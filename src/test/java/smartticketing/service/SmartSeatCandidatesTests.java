package smartticketing.service;

import org.junit.jupiter.api.Test;
import smartticketing.entity.Screen;
import smartticketing.entity.ShowtimeSeat;
import smartticketing.entity.enums.SeatPosition;
import smartticketing.entity.enums.SeatStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SmartSeatCandidatesTests {
    private List<ShowtimeSeat> row(Set<Integer> available) {
        var screen = new Screen(); screen.setId(1L);
        return DefaultSeatLayout.create(screen).stream().filter(s -> s.getSeatRow().equals("A")).map(s -> {
            s.setId(s.getSeatNumber().longValue());
            var item = new ShowtimeSeat(); item.setSeat(s);
            item.setStatus(available.contains(s.getSeatNumber()) ? SeatStatus.AVAILABLE : SeatStatus.BLOCKED);
            return item;
        }).toList();
    }

    private SmartSeatCandidates.Block best(Set<Integer> available, int party, SeatPosition preference) {
        return SmartSeatCandidates.analyze(row(available), 1L, party, List.of(preference)).blocks().stream()
                .min(Comparator.comparing(SmartSeatCandidates.Block::split)
                        .thenComparingInt(SmartSeatCandidates.Block::preferenceRank)
                        .thenComparingDouble(SmartSeatCandidates.Block::centerDistance)
                        .thenComparing(SmartSeatCandidates.Block::segment)
                        .thenComparingInt(SmartSeatCandidates.Block::firstPosition)).orElseThrow();
    }

    @Test void sidePreferenceChoosesInnerEdgeInsteadOfA1() {
        assertThat(best(Set.of(1,2,3,4,5,6,7,8,9,10,11,12), 1, SeatPosition.SIDE_FRONT).seatIds())
                .containsExactly(3L);
        assertThat(best(Set.of(1,2,10,11,12), 1, SeatPosition.SIDE_FRONT).seatIds()).containsExactly(10L);
    }

    @Test void centerAndContiguousPartiesPreferCentralSeats() {
        var all = Set.of(1,2,3,4,5,6,7,8,9,10,11,12);
        assertThat(best(all, 2, SeatPosition.MIDDLE_FRONT).seatIds()).containsExactly(6L,7L);
        assertThat(best(all, 2, SeatPosition.SIDE_FRONT).seatIds()).containsExactly(2L,3L);
    }

    @Test void occupiedSeatsDoNotShiftTheCenter() {
        assertThat(best(Set.of(4,5,6), 1, SeatPosition.MIDDLE_FRONT).seatIds()).containsExactly(6L);
    }

    @Test void splitPartiesAlsoPreferCentralSeats() {
        var selected = best(Set.of(1,2,5,6,8,9,11,12), 4, SeatPosition.MIDDLE_FRONT);
        assertThat(selected.split()).isTrue();
        assertThat(selected.seatIds()).containsExactly(5L,6L,8L,9L);
        assertThat(best(Set.of(1,2,3,10,11,12), 4, SeatPosition.SIDE_FRONT).seatIds())
                .containsExactly(2L,3L,10L,11L);
    }
}
