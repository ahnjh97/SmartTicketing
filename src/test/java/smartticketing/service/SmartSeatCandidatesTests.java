package smartticketing.service;

import org.junit.jupiter.api.Test;
import smartticketing.entity.Screen;
import smartticketing.entity.ShowtimeSeat;
import smartticketing.entity.enums.SeatPosition;
import smartticketing.entity.enums.SeatStatus;

import java.util.ArrayList;
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
                .min(SmartSeatCandidates.priorityOrder()
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

    private SmartSeatCandidates.Block bestSix(int... capacities) {
        var screen = new Screen(); screen.setId(1L);
        var inventory = new ArrayList<ShowtimeSeat>();
        int number = 0;
        for (int segment = 0; segment < capacities.length; segment++) {
            for (int offset = 1; offset <= capacities[segment]; offset++) {
                var seat = new smartticketing.entity.Seat();
                seat.setId((long) ++number); seat.setScreen(screen); seat.setSeatRow("A");
                seat.setSeatNumber(number); seat.setAdjacencySegment("S" + segment);
                seat.setPositionInSegment(offset);
                // 낮은 조합 순위의 중앙 선호석이 높은 조합 순위를 역전하면 안 된다.
                seat.setSeatPosition(segment < 2 ? SeatPosition.SIDE_FRONT : SeatPosition.MIDDLE_FRONT);
                var item = new ShowtimeSeat(); item.setSeat(seat); item.setStatus(SeatStatus.AVAILABLE);
                inventory.add(item);
            }
        }
        return SmartSeatCandidates.analyze(inventory, 1L, 6, List.of(SeatPosition.MIDDLE_FRONT))
                .blocks().stream().min(SmartSeatCandidates.priorityOrder()
                        .thenComparingDouble(SmartSeatCandidates.Block::centerDistance)).orElseThrow();
    }

    @Test void sixTogetherOutranksEverySplit() {
        var selected = bestSix(6, 3, 3, 2, 4, 2);
        assertThat(selected.split()).isFalse();
        assertThat(selected.seatIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test void threePlusThreeOutranksPreferredTwoPlusFourAndThreePairs() {
        var selected = bestSix(3, 3, 2, 4, 2);
        assertThat(selected.patternRank()).isEqualTo(1);
        // 4석 구간에서도 3석을 골라, 가능한 3+3 중 중앙에 가까운 조합을 선택한다.
        assertThat(selected.seatIds()).containsExactly(4L, 5L, 6L, 9L, 10L, 11L);
    }

    @Test void twoPlusFourIsUsedWhenThreePlusThreeIsUnavailable() {
        var selected = bestSix(2, 4, 2, 2);
        assertThat(selected.patternRank()).isEqualTo(2);
        assertThat(selected.seatIds()).contains(3L, 4L, 5L, 6L).hasSize(6);
    }

    @Test void threePairsAreTheLastFallback() {
        var selected = bestSix(2, 2, 2);
        assertThat(selected.patternRank()).isEqualTo(3);
        assertThat(selected.seatIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test void patternPriorityAlsoAppliesAcrossShowtimesBeforeSeatPreference() {
        var threePlusThree = bestSix(3, 3);
        var twoPlusFour = bestSix(0, 0, 2, 4);
        var threePairs = bestSix(0, 0, 2, 2, 2);
        assertThat(List.of(threePairs, twoPlusFour, threePlusThree).stream()
                .min(SmartSeatCandidates.priorityOrder()).orElseThrow()).isEqualTo(threePlusThree);
        assertThat(List.of(threePairs, twoPlusFour).stream()
                .min(SmartSeatCandidates.priorityOrder()).orElseThrow()).isEqualTo(twoPlusFour);
    }
}
