package smartticketing.auth;

import smartticketing.dto.auth.FindLoginIdsResponse;
import smartticketing.dto.auth.LoginRequest;
import smartticketing.dto.auth.SignupRequest;
import smartticketing.dto.auth.SocialSignupRequest;
import smartticketing.dto.auth.TokenResponse;
import smartticketing.dto.user.UserResponse;
import smartticketing.entity.UserSocialAccount;
import smartticketing.entity.Users;
import smartticketing.entity.enums.SocialProvider;
import smartticketing.entity.enums.UserStatus;
import smartticketing.repository.UserSocialAccountRepository;
import smartticketing.repository.UsersRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional
public class AuthService {

    private final AdminAccess adminAccess;
    private final UsersRepository users;
    private final UserSocialAccountRepository social;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final EmailVerificationService verification;

    public AuthService(
            UsersRepository users,
            UserSocialAccountRepository social,
            PasswordEncoder encoder,
            JwtService jwt,
            AdminAccess adminAccess,
            EmailVerificationService verification
    ) {
        this.verification = verification;
        this.adminAccess = adminAccess;
        this.users = users;
        this.social = social;
        this.encoder = encoder;
        this.jwt = jwt;
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
                java.util.List.of(),
                false,
                user.isEmailVerified()
        );
    }

    public FindLoginIdsResponse findLoginIds(String email, String verificationToken) {
        verification.consume(email, EmailPurpose.FIND_ID, verificationToken);
        var ids = users.findByEmailIgnoreCase(EmailVerificationService.normalize(email))
                .filter(u -> u.isEmailVerified() && u.getStatus() == UserStatus.ACTIVE)
                .map(Users::getLoginId).filter(id -> !adminAccess.isReserved(id))
                .map(java.util.List::of).orElse(java.util.List.of());
        return new FindLoginIdsResponse(ids);
    }

    public void resetPassword(String email, String verificationToken, String newPassword) {
        verification.consume(email, EmailPurpose.RESET_PASSWORD, verificationToken);
        Users user = users.findByEmailIgnoreCase(EmailVerificationService.normalize(email))
                .filter(u -> u.isEmailVerified() && u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> !adminAccess.isReserved(u.getLoginId()))
                .orElseThrow(() -> new IllegalArgumentException("인증된 이메일로 가입한 계정을 찾을 수 없습니다."));
        user.setPassword(encoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.save(user);
    }

    public TokenResponse signup(SignupRequest request) {

        String loginId = request.loginId().trim();

        if (adminAccess.isReserved(loginId)) {
            throw new IllegalArgumentException("사용할 수 없는 아이디입니다.");
        }
        if (users.existsByLoginId(loginId)) {
            throw new IllegalArgumentException(
                    "이미 사용 중인 아이디입니다."
            );
        }

        String email = EmailVerificationService.normalize(request.email());
        if (users.findByEmailIgnoreCase(email).isPresent() || social.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }
        verification.consume(email, EmailPurpose.SIGNUP, request.verificationToken());
        Users user = new Users();
        user.setEmail(email);
        user.setEmailVerified(true);

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
                        .or(() -> users.findByEmailIgnoreCase(loginId))
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
        String normalizedLoginId = loginId.trim();

        return users.existsByLoginId(normalizedLoginId)
                || users.findByEmailIgnoreCase(normalizedLoginId).isPresent()
                || social.existsByEmailIgnoreCase(normalizedLoginId);
    }

    @Transactional(readOnly = true)
    public TokenResponse socialLogin(SocialProvider provider, String providerUserId) {
        return social.findByProviderAndProviderUserId(provider, providerUserId).map(account -> {
            Users user = account.getUser();
            if (user.getStatus() != UserStatus.ACTIVE) throw new IllegalArgumentException("탈퇴한 계정입니다.");
            return createTokenResponse(user);
        }).orElse(null);
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

        if (adminAccess.isReserved(loginId)) {
            throw new IllegalArgumentException("사용할 수 없는 아이디입니다.");
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

        String verifiedEmail = EmailVerificationService.normalize(request.email());
        if (!verifiedEmail.equalsIgnoreCase(loginId)) {
            throw new IllegalArgumentException("소셜 가입 아이디와 인증한 이메일이 일치해야 합니다.");
        }
        if (users.findByEmailIgnoreCase(verifiedEmail).isPresent() || social.existsByEmailIgnoreCase(verifiedEmail)) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }
        verification.consume(verifiedEmail, EmailPurpose.SIGNUP, request.verificationToken());
        Users user = new Users();
        user.setName(oauthName.trim());
        user.setLoginId(loginId);
        user.setEmail(verifiedEmail);
        user.setEmailVerified(true);

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
                jwt.issueAccessToken(user.getId(), user.getTokenVersion()),
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
