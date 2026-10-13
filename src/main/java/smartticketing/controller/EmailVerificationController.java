package smartticketing.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import smartticketing.auth.*;

@RestController
@RequestMapping("/api/auth/email")
public class EmailVerificationController {
    private final EmailVerificationService verification;
    public EmailVerificationController(EmailVerificationService verification) { this.verification = verification; }
    public record SendRequest(@NotBlank @Email @Size(max=255) String email, @NotNull EmailPurpose purpose) {}
    public record VerifyRequest(@NotBlank @Email @Size(max=255) String email, @NotNull EmailPurpose purpose,
                                @NotBlank @Pattern(regexp="[0-9]{6}") String code) {}
    public record GrantResponse(String verificationToken) {}
    @PostMapping("/send")
    public void send(@Valid @RequestBody SendRequest request, jakarta.servlet.http.HttpServletRequest http) {
        verification.limitClient(http.getRemoteAddr(), false);
        verification.request(request.email(), request.purpose());
    }
    @PostMapping("/verify")
    public GrantResponse verify(@Valid @RequestBody VerifyRequest request, jakarta.servlet.http.HttpServletRequest http) {
        verification.limitClient(http.getRemoteAddr(), true);
        return new GrantResponse(verification.verify(request.email(), request.purpose(), request.code()));
    }
}
