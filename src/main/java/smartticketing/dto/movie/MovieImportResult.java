package smartticketing.dto.movie;

import java.util.List;

public record MovieImportResult (
    int savedCount,
    int skippedCount,
    List<Long> failedIds,
    int updatedCount
) {
}
