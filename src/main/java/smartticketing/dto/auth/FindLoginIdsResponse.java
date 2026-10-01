package smartticketing.dto.auth;

import java.util.List;

public record FindLoginIdsResponse(
        List<String> loginIds
) {
}
