package smartticketing.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.Users;
import smartticketing.repository.UsersRepository;

@Component
public class AdminAccountInitializer implements ApplicationRunner {
    private final UsersRepository users;
    private final PasswordEncoder encoder;
    private final String loginId;
    private final String password;

    public AdminAccountInitializer(UsersRepository users, PasswordEncoder encoder,
            @Value("${app.admin.login-id:admin}") String loginId,
            @Value("${app.admin.initial-password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.loginId = loginId.trim();
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (loginId.isBlank() || password.isBlank() || users.existsByLoginId(loginId)) return;
        if (password.length() < 4 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("ADMIN_INITIAL_PASSWORD must contain at least 4 characters and at most 72 UTF-8 bytes");
        }
        Users user = new Users();
        user.setLoginId(loginId);
        user.setName("관리자");
        user.setNickname("관리자");
        user.setPassword(encoder.encode(password));
        users.save(user);
    }
}
