package smartticketing.dto.auth;
import jakarta.validation.constraints.*;
public record PasswordResetRequest(
        @NotBlank @Email @Size(max=255) String email,
        @NotBlank @Size(max=100) String verificationToken,
        @NotBlank @Size(min=8, max=100) String newPassword) {}
