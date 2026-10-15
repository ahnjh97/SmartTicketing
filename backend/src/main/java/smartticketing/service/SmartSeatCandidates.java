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
    record SeatData(Long id, String row, Integer number, SeatPosition zone,
                    String segment, Integer position, boolean available) {
        SeatData withAvailability(boolean value) {
            return new SeatData(id, row, number, zone, segment, position, value);
        }
    }
    record Prepared(boolean complete, List<SeatData> sorted, Map<Long, Double> distances) {}

    // 좌석 조합 우선순위는 선호 위치 및 극장 순위보다 먼저 적용한다.
    static Comparator<Block> priorityOrder() {
        return Comparator.comparingInt(Block::patternRank).thenComparingInt(Block::preferenceRank);
    }

    static Analysis analyze(List<ShowtimeSeat> inventory, Long screenId, int party, List<SeatPosition> preferences) {
        return analyze(inventory, screenId, party, preferences, null);
    }

    static Analysis analyze(List<ShowtimeSeat> inventory, Long screenId, int party, List<SeatPosition> preferences, SeatPosition requiredZone) {
        return analyze(prepare(inventory, screenId), party, preferences, requiredZone);
    }

    static Prepared prepare(List<ShowtimeSeat> inventory, Long screenId) {
        return prepareSeats(inventory.stream().filter(i -> i.getSeat().isActive()
                && i.getSeat().getScreen().getId().equals(screenId)).map(i -> {
                    var seat = i.getSeat();
                    return new SeatData(seat.getId(), seat.getSeatRow(), seat.getSeatNumber(), seat.getSeatPosition(),
                            seat.getAdjacencySegment(), seat.getPositionInSegment(), i.getStatus() == SeatStatus.AVAILABLE
                            && i.getReservation() == null && i.getHoldExpiredAt() == null);
                }).toList());
    }

    static Prepared prepareSeats(List<SeatData> active) {
        var positions = new HashSet<Position>();
        for (var s : active) {
            if (s.row() == null || s.row().isBlank() || s.segment() == null
                    || s.segment().isBlank() || s.position() == null
                    || s.position() < 1 || s.zone() == null || s.number() == null
                    || !positions.add(new Position(s.row(), s.segment(), s.position())))
                return new Prepared(false, active, Map.of());
        }
        if (active.isEmpty()) return new Prepared(false, active, Map.of());
        // Occupied seats also define the row's center; availability must not move it.
        var rowBounds = new HashMap<String, IntSummaryStatistics>();
        for (var item : active) rowBounds.computeIfAbsent(item.row(), ignored -> new IntSummaryStatistics())
                .accept(item.number());
        var distances = new HashMap<Long, Double>();
        for (var seat : active) {
            var bounds = rowBounds.get(seat.row());
            distances.put(seat.id(), Math.abs(seat.number() - (bounds.getMin() + bounds.getMax()) / 2.0));
        }
        var sorted = active.stream().sorted(Comparator
                .comparing(SeatData::row)
                .thenComparing(SeatData::segment)
                .thenComparing(SeatData::position)).toList();
        return new Prepared(true, sorted, distances);
    }

    static Analysis analyze(Prepared prepared, int party, List<SeatPosition> preferences, SeatPosition requiredZone) {
        var sorted = prepared.sorted();
        int available = (int) sorted.stream().filter(i -> available(i, requiredZone)).count();
        if (!prepared.complete()) return new Analysis(false, available, List.of());
        var distances = prepared.distances();
        var blocks = new ArrayList<Block>();
        for (int start = 0; start + party <= sorted.size(); start++) {
            var first = sorted.get(start);
            var ids = new ArrayList<Long>();
            int rank = 0;
            for (int offset = 0; offset < party; offset++) {
                var seat = sorted.get(start + offset);
                if (!available(seat, requiredZone) || !seat.row().equals(first.row())
                        || !seat.segment().equals(first.segment())
                        || seat.position() != first.position() + offset) break;
                int preference = preferences.indexOf(seat.zone());
                // A block crossing preference zones is ranked by its least preferred seat.
                rank = Math.max(rank, preference < 0 ? preferences.size() : preference);
                ids.add(seat.id());
            }
            if (ids.size() == party) blocks.add(new Block(ids.stream().sorted().toList(), rank,
                    first.row(), first.segment(), first.position(), false,
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
                    var first = selected.getFirst();
                    blocks.add(new Block(selected.stream().map(i -> i.id()).sorted().toList(), rank,
                            first.row(), first.segment(), first.position(), true, distance(selected, distances), patternRank));
                    break;
                }
            }
        }
        return new Analysis(true, available, List.copyOf(blocks));
    }

    // index×묶음 사용 비트마스크를 메모해 좌석 조합의 전수 열거를 피한다.
    private static List<SeatData> split(List<SeatData> seats, List<Integer> pattern,
            List<SeatPosition> preferences, int rank, int index, int used,
            Map<Integer, List<SeatData>> memo, Map<Long, Double> distances, SeatPosition requiredZone) {
        if (used == (1 << pattern.size()) - 1) return List.of();
        if (index >= seats.size()) return null;
        int key = index * 8 + used;
        if (memo.containsKey(key)) return memo.get(key);
        List<SeatData> best = null;
        var first = seats.get(index);
        var triedSizes = new HashSet<Integer>();
        for (int part = 0; part < pattern.size(); part++) {
            int size = pattern.get(part);
            if ((used & (1 << part)) != 0 || !triedSizes.add(size) || index + size > seats.size()) continue;
            boolean valid = true;
            for (int offset = 0; offset < size; offset++) {
                var seat = seats.get(index + offset);
                int preference = preferences.indexOf(seat.zone());
                if (!available(seat, requiredZone) || !seat.row().equals(first.row())
                        || !seat.segment().equals(first.segment())
                        || seat.position() != first.position() + offset
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

    private static double distance(List<SeatData> seats, Map<Long, Double> distances) {
        return seats.stream().mapToDouble(s -> distances.get(s.id())).sum();
    }

    private static boolean available(SeatData s, SeatPosition requiredZone) {
        return (requiredZone == null || s.zone() == requiredZone)
                && s.available();
    }
}
