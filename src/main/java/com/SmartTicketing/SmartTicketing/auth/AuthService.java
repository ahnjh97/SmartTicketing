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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthService {

    private final UsersRepository users;
    private final UserSocialAccountRepository social;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(
            UsersRepository u,
            UserSocialAccountRepository s,
            PasswordEncoder e,
            JwtService j
    ) {
        users = u;
        social = s;
        encoder = e;
        jwt = j;
    }

    public static UserResponse toResponse(Users u) {
        return new UserResponse(
                u.getId(),
                u.getName(),
                u.getBirthDate(),
                u.getLoginId(),
                u.getEmail(),
                u.getNickname(),
                u.getAddress(),
                u.getStatus(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of()
        );
    }

    public UserResponse signup(SignupRequest r) {

        String loginId = r.loginId().trim();

        if (users.existsByLoginId(loginId)) {
            throw new IllegalArgumentException(
                    "이미 사용 중인 아이디입니다."
            );
        }

        Users u = new Users();

        u.setName(r.name());
        u.setBirthDate(r.birthDate());
        u.setLoginId(loginId);
        u.setPassword(
                encoder.encode(r.password())
        );
        u.setNickname(loginId);
        u.setStatus(UserStatus.ACTIVE);

        return toResponse(
                users.save(u)
        );
    }


    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest r) {

        String loginId = r.loginId().trim();

        Users u =
                users.findByLoginId(loginId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "아이디 또는 비밀번호가 올바르지 않습니다."
                                )
                        );

        if (
                u.getStatus() != UserStatus.ACTIVE
                        || u.getPassword() == null
                        || !encoder.matches(
                        r.password(),
                        u.getPassword()
                )
        ) {
            throw new IllegalArgumentException(
                    "아이디 또는 비밀번호가 올바르지 않습니다."
            );
        }

        return new TokenResponse(
                jwt.issueAccessToken(u.getId()),
                "Bearer",
                jwt.getExpirationSeconds()
        );
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
            SocialSignupRequest r
    ) {
        SocialProvider provider =
                SocialProvider.valueOf(
                        providerName.toUpperCase()
                );

        String loginId =
                r.loginId().trim();


        if (
                provider == SocialProvider.GOOGLE
                        || provider == SocialProvider.NAVER
        ) {
            if (
                    oauthEmail == null
                            || oauthEmail.isBlank()
                            || !oauthEmail.equalsIgnoreCase(loginId)
            ) {
                throw new IllegalArgumentException(
                        "소셜 로그인 이메일과 아이디가 일치하지 않습니다."
                );
            }
        }


        if (!loginId.matches(
                "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        )) {
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

        user.setName(
                oauthName == null || oauthName.isBlank()
                        ? "소셜회원"
                        : oauthName
        );


        user.setLoginId(loginId);


        user.setEmail(
                oauthEmail == null || oauthEmail.isBlank()
                        ? null
                        : oauthEmail
        );


        user.setPassword(
                encoder.encode(r.password())
        );


        user.setBirthDate(
                r.birthDate()
        );


        user.setNickname(loginId);

        user.setStatus(
                UserStatus.ACTIVE
        );

        user = users.save(user);


        UserSocialAccount account =
                new UserSocialAccount();

        account.setUser(user);
        account.setProvider(provider);
        account.setProviderUserId(providerUserId);
        account.setEmail(oauthEmail);

        social.save(account);


        return new TokenResponse(
                jwt.issueAccessToken(user.getId()),
                "Bearer",
                jwt.getExpirationSeconds()
        );
    }

    @Transactional(readOnly = true)
    public Users getUserForLink(Long userId) {

        Users u =
                users.findById(userId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "회원을 찾을 수 없습니다."
                                )
                        );

        if (
                u.getStatus()
                        != UserStatus.ACTIVE
        ) {
            throw new IllegalStateException(
                    "활성 상태의 회원만 연동할 수 있습니다."
            );
        }

        return u;
    }


    public void linkSocialAccount(
            Long userId,
            SocialProvider provider,
            String providerUserId,
            String email
    ) {
        Users u =
                users.findById(userId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "회원을 찾을 수 없습니다."
                                )
                        );

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

        account.setUser(u);
        account.setProvider(provider);
        account.setProviderUserId(providerUserId);
        account.setEmail(email);

        social.save(account);
    }
}