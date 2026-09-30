package com.SmartTicketing.SmartTicketing.auth;

import com.SmartTicketing.SmartTicketing.dto.auth.LoginRequest;
import com.SmartTicketing.SmartTicketing.dto.auth.SignupRequest;
import com.SmartTicketing.SmartTicketing.dto.auth.SocialSignupRequest;
import com.SmartTicketing.SmartTicketing.dto.auth.TokenResponse;
import com.SmartTicketing.SmartTicketing.dto.user.UserResponse;
import com.SmartTicketing.SmartTicketing.entity.UserSocialAccount;
import com.SmartTicketing.SmartTicketing.entity.Users;
import com.SmartTicketing.SmartTicketing.entity.enums.SocialProvider;
import com.SmartTicketing.SmartTicketing.entity.enums.UserStatus;
import com.SmartTicketing.SmartTicketing.repository.UserSocialAccountRepository;
import com.SmartTicketing.SmartTicketing.repository.UsersRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

@Service
@Transactional
public class AuthService {

    private final UsersRepository users;
    private final UserSocialAccountRepository social;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final StringRedisTemplate redis;
    private final PasswordResetMailService mailService;

    private static final String PASSWORD_RESET_KEY_PREFIX =
            "auth:password-reset:";
    private static final long PASSWORD_RESET_TTL_MINUTES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    public AuthService(
            UsersRepository users,
            UserSocialAccountRepository social,
            PasswordEncoder encoder,
            JwtService jwt,
            StringRedisTemplate redis,
            PasswordResetMailService mailService
    ) {
        this.users = users;
        this.social = social;
        this.encoder = encoder;
        this.jwt = jwt;
        this.redis = redis;
        this.mailService = mailService;
    }

    public static UserResponse toResponse(Users user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getBirthDate(),
                user.getLoginId(),
                user.getEmail(),
                user.getNickname(),
                user.getAddress(),
                user.getStatus(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of()
        );
    }

    @Transactional(readOnly = true)
    public String findLoginIdByEmail(String email) {
        String normalizedEmail = email.trim();

        Users user =
                users.findByEmail(normalizedEmail)
                        .filter(value ->
                                value.getStatus() == UserStatus.ACTIVE
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "가입된 이메일을 찾을 수 없습니다."
                                )
                        );

        return user.getLoginId();
    }

    public void sendPasswordResetCode(String email) {
        String normalizedEmail =
                email.trim().toLowerCase();

        users.findByEmail(normalizedEmail)
                .filter(user ->
                        user.getStatus() == UserStatus.ACTIVE
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "가입된 이메일을 찾을 수 없습니다."
                        )
                );

        String code =
                String.format(
                        "%06d",
                        RANDOM.nextInt(1_000_000)
                );

        redis.opsForValue().set(
                PASSWORD_RESET_KEY_PREFIX + normalizedEmail,
                code,
                PASSWORD_RESET_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        mailService.sendVerificationCode(
                normalizedEmail,
                code
        );
    }

    public void resetPassword(
            String email,
            String code,
            String newPassword
    ) {
        String normalizedEmail =
                email.trim().toLowerCase();

        String key =
                PASSWORD_RESET_KEY_PREFIX
                        + normalizedEmail;

        String savedCode =
                redis.opsForValue().get(key);

        if (
                savedCode == null
                        || !savedCode.equals(code.trim())
        ) {
            throw new IllegalArgumentException(
                    "인증코드가 올바르지 않거나 만료되었습니다."
            );
        }

        Users user =
                users.findByEmail(normalizedEmail)
                        .filter(value ->
                                value.getStatus() == UserStatus.ACTIVE
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "가입된 이메일을 찾을 수 없습니다."
                                )
                        );

        user.setPassword(
                encoder.encode(newPassword)
        );

        users.save(user);
        redis.delete(key);
    }

    public TokenResponse signup(SignupRequest request) {

        String loginId = request.loginId().trim();

        if (users.existsByLoginId(loginId)) {
            throw new IllegalArgumentException(
                    "이미 사용 중인 아이디입니다."
            );
        }

        Users user = new Users();

        user.setName(request.name().trim());
        user.setBirthDate(request.birthDate());
        user.setLoginId(loginId);
        user.setPassword(
                encoder.encode(request.password())
        );
        user.setNickname(loginId);
        user.setStatus(UserStatus.ACTIVE);

        user = users.save(user);

        return createTokenResponse(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {

        String loginId = request.loginId().trim();

        Users user =
                users.findByLoginId(loginId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "아이디 또는 비밀번호가 올바르지 않습니다."
                                )
                        );

        if (
                user.getStatus() != UserStatus.ACTIVE
                        || user.getPassword() == null
                        || !encoder.matches(
                        request.password(),
                        user.getPassword()
                )
        ) {
            throw new IllegalArgumentException(
                    "아이디 또는 비밀번호가 올바르지 않습니다."
            );
        }

        return createTokenResponse(user);
    }

    @Transactional(readOnly = true)
    public boolean isLoginIdTaken(String loginId) {
        return users.existsByLoginId(
                loginId.trim()
        );
    }

    public TokenResponse completeSocialSignup(
            String providerName,
            String providerUserId,
            String oauthName,
            String oauthEmail,
            SocialSignupRequest request
    ) {

        SocialProvider provider =
                parseProvider(providerName);

        String loginId =
                request.loginId().trim();

        if (
                providerUserId == null
                        || providerUserId.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "소셜 계정 정보를 확인할 수 없습니다."
            );
        }

        if (
                oauthName == null
                        || oauthName.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "소셜 계정의 이름을 확인할 수 없습니다."
            );
        }

        if (
                provider == SocialProvider.GOOGLE
                        || provider == SocialProvider.NAVER
        ) {

            if (
                    oauthEmail == null
                            || oauthEmail.isBlank()
            ) {
                throw new IllegalArgumentException(
                        "소셜 로그인 이메일을 가져오지 못했습니다."
                );
            }

            if (
                    !oauthEmail.equalsIgnoreCase(loginId)
            ) {
                throw new IllegalArgumentException(
                        "소셜 로그인 이메일과 아이디가 일치하지 않습니다."
                );
            }
        }

        if (!isValidEmail(loginId)) {
            throw new IllegalArgumentException(
                    "올바른 이메일 형식으로 입력해주세요."
            );
        }

        if (users.existsByLoginId(loginId)) {
            throw new IllegalArgumentException(
                    "이미 사용 중인 아이디입니다."
            );
        }

        if (
                social.findByProviderAndProviderUserId(
                        provider,
                        providerUserId
                ).isPresent()
        ) {
            throw new IllegalStateException(
                    "이미 가입된 소셜 계정입니다."
            );
        }

        if (
                oauthEmail != null
                        && !oauthEmail.isBlank()
                        && users.findByEmail(oauthEmail).isPresent()
        ) {
            throw new IllegalStateException(
                    "이미 가입된 이메일입니다."
            );
        }

        Users user = new Users();

        user.setName(oauthName.trim());
        user.setLoginId(loginId);

        user.setEmail(
                oauthEmail == null
                        || oauthEmail.isBlank()
                        ? null
                        : oauthEmail.trim()
        );

        user.setPassword(
                encoder.encode(
                        request.password()
                )
        );

        user.setBirthDate(
                request.birthDate()
        );

        user.setNickname(loginId);
        user.setStatus(UserStatus.ACTIVE);

        user = users.save(user);

        UserSocialAccount account =
                new UserSocialAccount();

        account.setUser(user);
        account.setProvider(provider);
        account.setProviderUserId(providerUserId);
        account.setEmail(oauthEmail);

        social.save(account);

        return createTokenResponse(user);
    }

    @Transactional(readOnly = true)
    public Users getUserForLink(Long userId) {

        Users user =
                users.findById(userId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "회원을 찾을 수 없습니다."
                                )
                        );

        if (
                user.getStatus()
                        != UserStatus.ACTIVE
        ) {
            throw new IllegalStateException(
                    "활성 상태의 회원만 연동할 수 있습니다."
            );
        }

        return user;
    }

    public void linkSocialAccount(
            Long userId,
            SocialProvider provider,
            String providerUserId,
            String email
    ) {

        Users user =
                users.findById(userId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "회원을 찾을 수 없습니다."
                                )
                        );

        if (
                user.getStatus()
                        != UserStatus.ACTIVE
        ) {
            throw new IllegalStateException(
                    "활성 상태의 회원만 연동할 수 있습니다."
            );
        }

        UserSocialAccount existing =
                social.findByProviderAndProviderUserId(
                        provider,
                        providerUserId
                ).orElse(null);

        if (existing != null) {

            if (
                    !existing.getUser()
                            .getId()
                            .equals(userId)
            ) {
                throw new IllegalStateException(
                        "이미 다른 회원에게 연동된 소셜 계정입니다."
                );
            }

            return;
        }

        if (
                social.findByUserIdAndProvider(
                        userId,
                        provider
                ).isPresent()
        ) {
            throw new IllegalStateException(
                    "이미 같은 제공자가 연동되어 있습니다."
            );
        }

        UserSocialAccount account =
                new UserSocialAccount();

        account.setUser(user);
        account.setProvider(provider);
        account.setProviderUserId(providerUserId);
        account.setEmail(email);

        social.save(account);
    }

    private TokenResponse createTokenResponse(
            Users user
    ) {
        return new TokenResponse(
                jwt.issueAccessToken(user.getId()),
                "Bearer",
                jwt.getExpirationSeconds()
        );
    }

    private SocialProvider parseProvider(
            String providerName
    ) {

        if (
                providerName == null
                        || providerName.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "소셜 로그인 제공자가 없습니다."
            );
        }

        try {
            return SocialProvider.valueOf(
                    providerName.toUpperCase()
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "지원하지 않는 소셜 로그인 제공자입니다."
            );
        }
    }

    private boolean isValidEmail(String email) {
        return email != null
                && email.matches(
                "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        );
    }
}