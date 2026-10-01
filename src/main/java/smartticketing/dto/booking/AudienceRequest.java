package smartticketing.dto.booking;

import jakarta.validation.constraints.*;

/** 본인 포함 인원. 보호자는 단순 성인 동행과 구분하여 명시적으로 확인한다. */
public record AudienceRequest(@NotNull @Min(0) @Max(6) Integer adultCount,
        @NotNull @Min(0) @Max(6) Integer youthCount,
        @NotNull Boolean companionsEligible, @NotNull Boolean guardianAccompanying) {}
