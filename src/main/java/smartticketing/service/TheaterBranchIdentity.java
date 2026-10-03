package smartticketing.service;

import smartticketing.entity.Theater;
import smartticketing.entity.enums.TheaterBrand;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Verified branch aliases, not a distance-based deduplication heuristic. */
final class TheaterBranchIdentity {
    private TheaterBranchIdentity() {}

    private static final List<Branch> BRANCHES = List.of(
            new Branch("CGV용산아이파크몰",
                    Set.of("CGV씨네드쉐프용산아이파크몰", "CGV씨네드쉐프용산"),
                    Set.of("서울용산구한강대로23길55")),
            new Branch("CGV압구정",
                    Set.of("CGV압구정본관", "CGV씨네드쉐프압구정"),
                    Set.of("서울강남구압구정로30길45", "서울강남구논현로848"))
    );

    static boolean excludedFromCollection(TheaterBrand brand, String name, String location) {
        return brand == TheaterBrand.CGV && BRANCHES.stream().anyMatch(branch ->
                branch.aliases().contains(normalize(name)) && branch.addresses().contains(address(location)));
    }

    static boolean isAliasOf(Theater alias, Theater representative) {
        if (alias.getBrand() != TheaterBrand.CGV || representative.getBrand() != TheaterBrand.CGV) return false;
        return BRANCHES.stream().anyMatch(branch ->
                branch.name().equals(normalize(representative.getName()))
                        && branch.aliases().contains(normalize(alias.getName()))
                        && branch.addresses().contains(address(alias.getAddress()))
                        && branch.addresses().contains(address(representative.getAddress())));
    }

    private static String address(String value) {
        return normalize(value).replace("서울특별시", "서울");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private record Branch(String name, Set<String> aliases, Set<String> addresses) {}
}
