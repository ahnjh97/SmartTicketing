package smartticketing.auth;

import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import smartticketing.config.JwtConfig;
import smartticketing.dto.auth.*;
import smartticketing.entity.Users;
import smartticketing.repository.*;
import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("core")
class EmailAccountTests {
    private final UsersRepository users = mock(UsersRepository.class);
    private final UserSocialAccountRepository social = mock(UserSocialAccountRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final EmailVerificationService proof = mock(EmailVerificationService.class);
    private final JwtConfig config = new JwtConfig();
    private final javax.crypto.SecretKey key = config.jwtSecretKey("email-account-test-secret-at-least-32-bytes");
    private final JwtService jwt = new JwtService(config.jwtEncoder(key), new MockEnvironment());
    private final AuthService auth = new AuthService(users, social, encoder, jwt, new AdminAccess(users, "admin"), proof);

    @Test void unauthenticatedLegacyPayloadCannotPassValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new PasswordResetRequest(null, null, "password123")).isEmpty());
            assertFalse(validator.validate(new SignupRequest("name", LocalDate.of(2000,1,1), "member", "password123", null, null)).isEmpty());
            assertFalse(validator.validate(new SocialSignupRequest("member@example.com", "password123", LocalDate.of(2000,1,1), null, null)).isEmpty());
        }
    }
    @Test void invalidEmailProofCannotCreateAnAccount() {
        doThrow(new IllegalArgumentException("invalid proof")).when(proof).consume(any(), any(), any());
        assertThrows(IllegalArgumentException.class, () -> auth.signup(new SignupRequest("name", LocalDate.of(2000,1,1), "member", "password123", "member@example.com", "fake")));
        verify(users, never()).save(any());
    }
    @Test void signupSavesOnlyTheVerifiedNormalizedEmail() {
        when(users.save(any())).thenAnswer(call -> { Users u=call.getArgument(0);u.setId(8L);return u; });
        auth.signup(new SignupRequest("name", LocalDate.of(2000,1,1), "member", "password123", "MEMBER@Example.com", "proof"));
        verify(proof).consume("member@example.com", EmailPurpose.SIGNUP, "proof");
        verify(users).save(argThat(u -> u.isEmailVerified() && u.getEmail().equals("member@example.com")));
    }
    @Test void passwordResetRejectsAllEarlierTokensButNewLoginWorks() {
        Users u=new Users();u.setId(8L);u.setLoginId("member");u.setEmail("member@example.com");u.setEmailVerified(true);u.setPassword("old");
        when(users.findByEmailIgnoreCase(u.getEmail())).thenReturn(Optional.of(u));
        when(users.findById(8L)).thenReturn(Optional.of(u));
        when(users.findByLoginId("member")).thenReturn(Optional.of(u));
        when(encoder.encode("newPassword123")).thenReturn("newHash");
        when(encoder.matches("newPassword123", "newHash")).thenReturn(true);
        var decoder=config.jwtDecoder(key, users);
        String old=jwt.issueAccessToken(8L);
        assertDoesNotThrow(() -> decoder.decode(old));
        auth.resetPassword(u.getEmail(), "proof", "newPassword123");
        verify(proof).consume(u.getEmail(), EmailPurpose.RESET_PASSWORD, "proof");
        assertThrows(JwtException.class, () -> decoder.decode(old));
        assertDoesNotThrow(() -> decoder.decode(auth.login(new LoginRequest("member", "newPassword123")).accessToken()));
    }
    @Test void unverifiedOrAdminEmailCannotResetPassword() {
        Users u=new Users();u.setLoginId("member");u.setEmail("member@example.com");
        when(users.findByEmailIgnoreCase(u.getEmail())).thenReturn(Optional.of(u));
        assertThrows(IllegalArgumentException.class, () -> auth.resetPassword(u.getEmail(), "proof", "password123"));
        u.setLoginId("admin");u.setEmailVerified(true);
        assertThrows(IllegalArgumentException.class, () -> auth.resetPassword(u.getEmail(), "proof", "password123"));
        verify(users, never()).save(any());
    }
}
