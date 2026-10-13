package smartticketing.dto.auth;
import jakarta.validation.constraints.*;
public record FindLoginIdsRequest(
        @NotBlank @Email @Size(max=255) String email,
        @NotBlank @Size(max=100) String verificationToken) {}
