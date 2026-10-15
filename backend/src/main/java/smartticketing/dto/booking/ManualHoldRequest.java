package smartticketing.dto.booking;

import jakarta.validation.constraints.*;
import java.util.List;

public record ManualHoldRequest(@NotNull @Size(min = 1, max = 6) List<@NotNull @Min(1) Long> seatIds) {}
