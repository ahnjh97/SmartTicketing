package smartticketing.service;

import java.util.*;

/** 같은 회차에서 허용하는 연석 묶음. 1인 분할은 허용하지 않는다. */
final class SeatPartyRules {
    private SeatPartyRules() {}

    static List<List<Integer>> patterns(int party) {
        return switch (party) {
            case 1, 2, 3 -> List.of(List.of(party));
            case 4 -> List.of(List.of(4), List.of(2, 2));
            case 5 -> List.of(List.of(5), List.of(2, 3));
            case 6 -> List.of(List.of(6), List.of(3, 3), List.of(2, 4), List.of(2, 2, 2));
            default -> List.of();
        };
    }

    static boolean allows(List<Integer> runs) {
        var sorted = runs.stream().sorted().toList();
        return patterns(runs.stream().mapToInt(Integer::intValue).sum()).contains(sorted);
    }

    static List<Integer> bookableParties(List<Integer> capacities) {
        var result = new ArrayList<Integer>();
        for (int party = 1; party <= 6; party++)
            for (var pattern : patterns(party)) {
                if (fits(pattern, 0, capacities.stream().mapToInt(Integer::intValue).toArray())) {
                    result.add(party); break;
                }
            }
        return List.copyOf(result);
    }

    private static boolean fits(List<Integer> pattern, int index, int[] capacities) {
        if (index == pattern.size()) return true;
        int size = pattern.get(index);
        var tried = new HashSet<Integer>();
        for (int i = 0; i < capacities.length; i++) {
            if (capacities[i] < size || !tried.add(capacities[i])) continue;
            capacities[i] -= size;
            boolean fits = fits(pattern, index + 1, capacities);
            capacities[i] += size;
            if (fits) return true;
        }
        return false;
    }
}
