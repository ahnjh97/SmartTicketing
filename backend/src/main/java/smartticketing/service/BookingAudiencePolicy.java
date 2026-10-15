package smartticketing.service;

import smartticketing.dto.booking.AudienceRequest;
import smartticketing.entity.*;
import java.time.*;
import java.util.Locale;
import static smartticketing.service.BookingHoldService.reject;

/** 한국 등급: 12/15세는 만 나이, 청불은 2024-05-01 개정의 연도 기준. */
public final class BookingAudiencePolicy {
    private BookingAudiencePolicy() {}

    public static String rating(String value) {
        if (value == null) { reject(409, "영화 관람등급을 확인할 수 없습니다."); }
        return switch (value.replaceAll("\\s", "").toUpperCase(Locale.ROOT)) {
            case "ALL", "전체", "전체관람가" -> "ALL";
            case "12", "12세", "12세이상관람가" -> "12";
            case "15", "15세", "15세이상관람가" -> "15";
            case "18", "19", "청불", "청소년관람불가" -> "19";
            default -> throw new BookingRejection(409, "미확인 또는 지원하지 않는 관람등급입니다.");
        };
    }

    public static void validate(Users user, String rating, LocalDate date, int party, AudienceRequest audience) {
        if (party < 1 || party > 6 || audience == null || audience.adultCount() == null || audience.youthCount() == null
                || audience.adultCount() < 0 || audience.adultCount() > 6 || audience.youthCount() < 0 || audience.youthCount() > 6
                || audience.adultCount() + audience.youthCount() != party)
            reject(400, "성인과 청소년 인원의 합은 전체 인원 1~6명과 같아야 합니다.");
        LocalDate birth = user.getBirthDate();
        if (birth == null || birth.isAfter(date)) reject(400, "회원 생년월일을 먼저 확인해주세요.");
        boolean youth = date.getYear() - birth.getYear() < 19;
        if (youth && audience.adultCount() > 0)
            reject(400, "청소년 회원은 성인 인원을 선택할 수 없습니다.");
        if ((youth && audience.youthCount() == 0) || (!youth && audience.adultCount() == 0))
            reject(400, "예매자 본인을 해당 연령 인원에 포함해주세요.");
        if (rating.equals("19")) {
            if (youth || audience.youthCount() > 0) reject(400, "청소년관람불가 영화는 보호자 동반으로도 관람할 수 없습니다.");
        } else if (!rating.equals("ALL")) {
            int age = Period.between(birth, date).getYears();
            if (age < Integer.parseInt(rating))
                reject(400, "회원 생년월일 기준으로 해당 영화의 관람 가능 연령이 아닙니다.");
        }
    }

    public static AudienceRequest audience(BookingRequestGroup group) {
        return new AudienceRequest(group.getAdultCount(), group.getYouthCount(), group.getCompanionsEligible(), group.getGuardianAccompanying());
    }

    static void revalidate(BookingRequestGroup group, LocalDate date) {
        String rating = rating(group.getMovie().getRating());
        if (!rating.equals(group.getRatingSnapshot())) reject(409, "관람등급이 변경되어 새로운 관람 요청이 필요합니다.");
        validate(group.getUser(), rating, date, group.getPartySize(), audience(group));
    }
}
