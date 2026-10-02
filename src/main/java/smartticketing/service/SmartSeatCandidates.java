package smartticketing.service;

import smartticketing.entity.ShowtimeSeat;
import smartticketing.entity.enums.SeatPosition;
import smartticketing.entity.enums.SeatStatus;

import java.util.*;

/** Pure selection: one entire contiguous block, with no inventory mutation. */
final class SmartSeatCandidates {
    record Block(List<Long> seatIds, int preferenceRank, String row, String segment, int firstPosition) {}
    record Analysis(boolean layoutComplete, int available, List<Block> blocks) {}
    private record Position(String row, String segment, Integer offset) {}

    static Analysis analyze(List<ShowtimeSeat> inventory, Long screenId, int party, List<SeatPosition> preferences) {
        var active = inventory.stream().filter(i -> i.getSeat().isActive()
                && i.getSeat().getScreen().getId().equals(screenId)).toList();
        int available = (int) active.stream().filter(SmartSeatCandidates::available).count();
        var positions = new HashSet<Position>();
        for (var item : active) {
            var s = item.getSeat();
            if (s.getSeatRow() == null || s.getSeatRow().isBlank() || s.getAdjacencySegment() == null
                    || s.getAdjacencySegment().isBlank() || s.getPositionInSegment() == null
                    || s.getPositionInSegment() < 1 || s.getSeatPosition() == null
                    || !positions.add(new Position(s.getSeatRow(), s.getAdjacencySegment(), s.getPositionInSegment())))
                return new Analysis(false, available, List.of());
        }
        if (active.isEmpty()) return new Analysis(false, 0, List.of());
        var sorted = active.stream().sorted(Comparator
                .comparing((ShowtimeSeat i) -> i.getSeat().getSeatRow())
                .thenComparing(i -> i.getSeat().getAdjacencySegment())
                .thenComparing(i -> i.getSeat().getPositionInSegment())).toList();
        var blocks = new ArrayList<Block>();
        for (int start = 0; start + party <= sorted.size(); start++) {
            var first = sorted.get(start).getSeat();
            var ids = new ArrayList<Long>();
            int rank = 0;
            for (int offset = 0; offset < party; offset++) {
                var item = sorted.get(start + offset); var seat = item.getSeat();
                if (!available(item) || !seat.getSeatRow().equals(first.getSeatRow())
                        || !seat.getAdjacencySegment().equals(first.getAdjacencySegment())
                        || seat.getPositionInSegment() != first.getPositionInSegment() + offset) break;
                int preference = preferences.indexOf(seat.getSeatPosition());
                // A block crossing preference zones is ranked by its least preferred seat.
                rank = Math.max(rank, preference < 0 ? preferences.size() : preference);
                ids.add(seat.getId());
            }
            if (ids.size() == party) blocks.add(new Block(ids.stream().sorted().toList(), rank,
                    first.getSeatRow(), first.getAdjacencySegment(), first.getPositionInSegment()));
        }
        return new Analysis(true, available, List.copyOf(blocks));
    }

    private static boolean available(ShowtimeSeat s) {
        return s.getStatus() == SeatStatus.AVAILABLE && s.getReservation() == null && s.getHoldExpiredAt() == null;
    }
}
