package com.SmartTicketing.SmartTicketing.auth;

import com.SmartTicketing.SmartTicketing.dto.auth.LoginRequest;
import com.SmartTicketing.SmartTicketing.dto.auth.SignupRequest;
import com.SmartTicketing.SmartTicketing.dto.auth.TokenResponse;
import com.SmartTicketing.SmartTicketing.dto.user.UserResponse;
import com.SmartTicketing.SmartTicketing.entity.*;
import com.SmartTicketing.SmartTicketing.entity.enums.*;
import com.SmartTicketing.SmartTicketing.repository.*;
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

    public AuthService(UsersRepository u, UserSocialAccountRepository s, PasswordEncoder e, JwtService j) {
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
        if (users.existsByLoginId(r.loginId())) throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        Users u = new Users();
        u.setName(r.name());
        u.setLoginId(r.loginId());
        u.setPassword(encoder.encode(r.password()));
        u.setNickname(r.loginId());
        u.setStatus(UserStatus.ACTIVE);
        return toResponse(users.save(u));
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest r) {
        Users u = users.findByLoginId(r.loginId()).orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다."));
        if (u.getStatus() != UserStatus.ACTIVE || u.getPassword() == null || !encoder.matches(r.password(), u.getPassword()))
            throw new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다.");
        return new TokenResponse(jwt.issueAccessToken(u.getId()), "Bearer", jwt.getExpirationSeconds());
    }

    @Transactional(readOnly = true)
    public boolean isLoginIdTaken(String loginId) {
        return users.existsByLoginId(loginId);
    }

    public Users findOrCreateSocialUser(SocialProvider provider, String providerUserId, String email, String name) {
        UserSocialAccount a = social.findByProviderAndProviderUserId(provider, providerUserId).orElse(null);
        if (a != null) {
            if (a.getUser().getStatus() != UserStatus.ACTIVE) throw new IllegalStateException("탈퇴한 회원의 소셜 계정입니다.");
            return a.getUser();
        }
        if (users.findByEmail(email).isPresent())
            throw new IllegalStateException("이미 가입된 이메일입니다. 일반 로그인 후 소셜 계정을 연동해주세요.");
        Users u = new Users();
        u.setName(name == null || name.isBlank() ? email : name);
        u.setEmail(email);
        u.setNickname(email);
        u.setStatus(UserStatus.ACTIVE);
        u = users.save(u);
        UserSocialAccount sa = new UserSocialAccount();
        sa.setUser(u);
        sa.setProvider(provider);
        sa.setProviderUserId(providerUserId);
        sa.setEmail(email);
        social.save(sa);
        return u;
    }

    @Transactional(readOnly = true)
    public Users getUserForLink(Long userId) {
        Users u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        if (u.getStatus() != UserStatus.ACTIVE) throw new IllegalStateException("활성 상태의 회원만 연동할 수 있습니다.");
        return u;
    }

    public void linkSocialAccount(Long userId, SocialProvider provider, String providerUserId, String email) {
        Users u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        UserSocialAccount existing = social.findByProviderAndProviderUserId(provider, providerUserId).orElse(null);
        if (existing != null) {
            if (!existing.getUser().getId().equals(userId)) throw new IllegalStateException("이미 다른 회원에게 연동된 소셜 계정입니다.");
            return;
        }
        if (social.findByUserIdAndProvider(userId, provider).isPresent())
            throw new IllegalStateException("이미 같은 제공자가 연동되어 있습니다.");
        UserSocialAccount sa = new UserSocialAccount();
        sa.setUser(u);
        sa.setProvider(provider);
        sa.setProviderUserId(providerUserId);
        sa.setEmail(email);
        social.save(sa);
    }
}
