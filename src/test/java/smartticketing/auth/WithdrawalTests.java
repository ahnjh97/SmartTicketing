package smartticketing.auth;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import smartticketing.config.JwtConfig;
import smartticketing.controller.UserController;
import smartticketing.dto.auth.LoginRequest;
import smartticketing.entity.Users;
import smartticketing.entity.enums.UserStatus;
import smartticketing.repository.*;
import smartticketing.service.UserService;
import smartticketing.util.CurrentUser;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("core")
class WithdrawalTests {
    @Test
    void withdrawalInvalidatesSessionAndPreviouslyIssuedTokensAndBlocksLogin() {
        var users = mock(UsersRepository.class);
        var user = new Users();
        user.setId(7L);
        user.setLoginId("member");
        user.setPassword("encoded");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.findByLoginId("member")).thenReturn(Optional.of(user));
        var config = new JwtConfig();
        var key = config.jwtSecretKey("withdrawal-test-secret-at-least-32-bytes");
        var issuer = new JwtService(config.jwtEncoder(key), new MockEnvironment());
        var decoder = config.jwtDecoder(key, users);
        var token = issuer.issueAccessToken(7L);
        var otherSessionToken = issuer.issueAccessToken(7L);
        var principal = decoder.decode(token);
        var social = mock(UserSocialAccountRepository.class);
        var admin = new AdminAccess(users, "admin");
        var service = new UserService(users, social, mock(UserPreferredTheaterRepository.class),
                mock(UserPreferredSeatRepository.class), mock(TheaterRepository.class),
                mock(UserNearbyTheaterRepository.class), admin);
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("OAUTH2_LINK_USER_ID", 7L);

        assertEquals(204, new UserController(service, new CurrentUser())
                .withdraw(principal, request).getStatusCode().value());
        assertEquals(UserStatus.WITHDRAWN, user.getStatus());
        assertNull(request.getSession(false));
        assertThrows(JwtException.class, () -> decoder.decode(token));
        assertThrows(JwtException.class, () -> decoder.decode(otherSessionToken));
        var auth = new AuthService(users, social, mock(PasswordEncoder.class), issuer, admin);
        assertThrows(IllegalArgumentException.class, () -> auth.login(new LoginRequest("member", "password")));
        assertThrows(JwtException.class, () -> decoder.decode(issuer.issueAccessToken(999L)));
    }
}
