package smartticketing.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import smartticketing.entity.Users;
import smartticketing.entity.enums.UserStatus;
import smartticketing.repository.UsersRepository;

@Service
public class AdminAccess {
    private final UsersRepository users;
    private final String loginId;

    public AdminAccess(UsersRepository users, @Value("${app.admin.login-id:admin}") String loginId) {
        this.users = users;
        this.loginId = loginId.trim();
    }

    public boolean isReserved(String candidate) {
        return !loginId.isBlank() && candidate != null && loginId.equalsIgnoreCase(candidate.trim());
    }

    public boolean isAdmin(Users user) {
        return user != null && user.getStatus() == UserStatus.ACTIVE
                && !loginId.isBlank() && loginId.equals(user.getLoginId());
    }

    public boolean permits(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) return false;
        try {
            return users.findById(Long.valueOf(token.getToken().getSubject())).map(this::isAdmin).orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
