package smartticketing.dto.booking;

import jakarta.validation.constraints.*;

/** 본인 포함 인원. 이전 클라이언트의 확인 필드는 호환용으로만 받는다. */
public record AudienceRequest(@NotNull @Min(0) @Max(6) Integer adultCount,
        @NotNull @Min(0) @Max(6) Integer youthCount,
        Boolean companionsEligible, Boolean guardianAccompanying) {}
