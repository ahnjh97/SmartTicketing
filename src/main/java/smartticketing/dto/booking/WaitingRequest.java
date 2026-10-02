package smartticketing.dto.booking;

import jakarta.validation.constraints.*;
import java.util.List;

public record WaitingRequest(@NotEmpty List<@NotNull @Positive Long> showtimeIds) {}
