package smartticketing.service;

import smartticketing.entity.ShowtimeSeat;
import smartticketing.entity.enums.SeatPosition;
import smartticketing.entity.enums.SeatStatus;

import java.util.*;

/** Pure selection: full contiguous seating first, then permitted disjoint blocks. */
final class SmartSeatCandidates {
    record Block(List<Long> seatIds, int preferenceRank, String row, String segment, int firstPosition, boolean split, double centerDistance, int patternRank) {}
    record Analysis(boolean layoutComplete, int available, List<Block> blocks) {}
    private record Position(String row, String segment, Integer offset) {}

    // 좌석 조합 우선순위는 선호 위치 및 극장 순위보다 먼저 적용한다.
    static Comparator<Block> priorityOrder() {
        return Comparator.comparingInt(Block::patternRank).thenComparingInt(Block::preferenceRank);
    }

    static Analysis analyze(List<ShowtimeSeat> inventory, Long screenId, int party, List<SeatPosition> preferences) {
        return analyze(inventory, screenId, party, preferences, null);
    }

    static Analysis analyze(List<ShowtimeSeat> inventory, Long screenId, int party, List<SeatPosition> preferences, SeatPosition requiredZone) {
        var active = inventory.stream().filter(i -> i.getSeat().isActive()
                && i.getSeat().getScreen().getId().equals(screenId)).toList();
        int available = (int) active.stream().filter(i -> available(i, requiredZone)).count();
        var positions = new HashSet<Position>();
        for (var item : active) {
            var s = item.getSeat();
            if (s.getSeatRow() == null || s.getSeatRow().isBlank() || s.getAdjacencySegment() == null
                    || s.getAdjacencySegment().isBlank() || s.getPositionInSegment() == null
                    || s.getPositionInSegment() < 1 || s.getSeatPosition() == null || s.getSeatNumber() == null
                    || !positions.add(new Position(s.getSeatRow(), s.getAdjacencySegment(), s.getPositionInSegment())))
                return new Analysis(false, available, List.of());
        }
        if (active.isEmpty()) return new Analysis(false, 0, List.of());
        // Occupied seats also define the row's center; availability must not move it.
        var rowBounds = new HashMap<String, IntSummaryStatistics>();
        for (var item : active) rowBounds.computeIfAbsent(item.getSeat().getSeatRow(), ignored -> new IntSummaryStatistics())
                .accept(item.getSeat().getSeatNumber());
        var distances = new HashMap<Long, Double>();
        for (var item : active) {
            var seat = item.getSeat();
            var bounds = rowBounds.get(seat.getSeatRow());
            distances.put(seat.getId(), Math.abs(seat.getSeatNumber() - (bounds.getMin() + bounds.getMax()) / 2.0));
        }
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
                if (!available(item, requiredZone) || !seat.getSeatRow().equals(first.getSeatRow())
                        || !seat.getAdjacencySegment().equals(first.getAdjacencySegment())
                        || seat.getPositionInSegment() != first.getPositionInSegment() + offset) break;
                int preference = preferences.indexOf(seat.getSeatPosition());
                // A block crossing preference zones is ranked by its least preferred seat.
                rank = Math.max(rank, preference < 0 ? preferences.size() : preference);
                ids.add(seat.getId());
            }
            if (ids.size() == party) blocks.add(new Block(ids.stream().sorted().toList(), rank,
                    first.getSeatRow(), first.getAdjacencySegment(), first.getPositionInSegment(), false,
                    ids.stream().mapToDouble(distances::get).sum(), 0));
        }
        // 전체 연석이 있으면 해당 회차의 분할 후보는 만들 필요가 없다.
        if (blocks.isEmpty()) {
            var patterns = SeatPartyRules.patterns(party);
            for (int patternRank = 0; patternRank < patterns.size(); patternRank++) {
                var pattern = patterns.get(patternRank);
                if (pattern.size() == 1) continue;
                for (int rank = 0; rank <= preferences.size(); rank++) {
                    var selected = split(sorted, pattern, preferences, rank, 0, 0, new HashMap<>(), distances, requiredZone);
                    if (selected == null) continue;
                    var first = selected.getFirst().getSeat();
                    blocks.add(new Block(selected.stream().map(i -> i.getSeat().getId()).sorted().toList(), rank,
                            first.getSeatRow(), first.getAdjacencySegment(), first.getPositionInSegment(), true, distance(selected, distances), patternRank));
                    break;
                }
            }
        }
        return new Analysis(true, available, List.copyOf(blocks));
    }

    // index×묶음 사용 비트마스크를 메모해 좌석 조합의 전수 열거를 피한다.
    private static List<ShowtimeSeat> split(List<ShowtimeSeat> seats, List<Integer> pattern,
            List<SeatPosition> preferences, int rank, int index, int used,
            Map<Integer, List<ShowtimeSeat>> memo, Map<Long, Double> distances, SeatPosition requiredZone) {
        if (used == (1 << pattern.size()) - 1) return List.of();
        if (index >= seats.size()) return null;
        int key = index * 8 + used;
        if (memo.containsKey(key)) return memo.get(key);
        List<ShowtimeSeat> best = null;
        var first = seats.get(index).getSeat();
        var triedSizes = new HashSet<Integer>();
        for (int part = 0; part < pattern.size(); part++) {
            int size = pattern.get(part);
            if ((used & (1 << part)) != 0 || !triedSizes.add(size) || index + size > seats.size()) continue;
            boolean valid = true;
            for (int offset = 0; offset < size; offset++) {
                var item = seats.get(index + offset); var seat = item.getSeat();
                int preference = preferences.indexOf(seat.getSeatPosition());
                if (!available(item, requiredZone) || !seat.getSeatRow().equals(first.getSeatRow())
                        || !seat.getAdjacencySegment().equals(first.getAdjacencySegment())
                        || seat.getPositionInSegment() != first.getPositionInSegment() + offset
                        || (preference < 0 ? preferences.size() : preference) > rank) { valid = false; break; }
            }
            if (!valid) continue;
            var tail = split(seats, pattern, preferences, rank, index + size, used | (1 << part), memo, distances, requiredZone);
            if (tail != null) {
                var result = new ArrayList<>(seats.subList(index, index + size)); result.addAll(tail);
                if (best == null || distance(result, distances) < distance(best, distances)) best = result;
            }
        }
        var skipped = split(seats, pattern, preferences, rank, index + 1, used, memo, distances, requiredZone);
        if (skipped != null && (best == null || distance(skipped, distances) < distance(best, distances))) best = skipped;
        memo.put(key, best);
        return best;
    }

    private static double distance(List<ShowtimeSeat> seats, Map<Long, Double> distances) {
        return seats.stream().mapToDouble(s -> distances.get(s.getSeat().getId())).sum();
    }

    private static boolean available(ShowtimeSeat s, SeatPosition requiredZone) {
        return (requiredZone == null || s.getSeat().getSeatPosition() == requiredZone)
                && s.getStatus() == SeatStatus.AVAILABLE && s.getReservation() == null && s.getHoldExpiredAt() == null;
    }
}
