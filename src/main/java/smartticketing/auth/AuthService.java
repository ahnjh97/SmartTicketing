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

    public AuthService(
            UsersRepository users,
            UserSocialAccountRepository social,
            PasswordEncoder encoder,
            JwtService jwt,
            AdminAccess adminAccess
    ) {
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
                false
        );
    }

    @Transactional(readOnly = true)
    public FindLoginIdsResponse findLoginIds(String name) {
        String normalizedName = name.trim();

        java.util.List<String> loginIds =
                users.findAllByNameAndStatusOrderByIdAsc(
                                normalizedName,
                                UserStatus.ACTIVE
                        )
                        .stream()
                        .map(Users::getLoginId)
                        .filter(java.util.Objects::nonNull)
                        .filter(loginId -> !loginId.isBlank())
                        .toList();

        return new FindLoginIdsResponse(loginIds);
    }

    public void resetPassword(
            String name,
            String loginId,
            String newPassword
    ) {
        String normalizedName = name.trim();
        String normalizedLoginId = loginId.trim();

        Users user = users.findByNameAndLoginId(
                        normalizedName,
                        normalizedLoginId
                )
                .filter(value -> value.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "이름과 아이디가 일치하는 가입 계정을 찾을 수 없습니다."
                        )
                );

        if (adminAccess.isReserved(user.getLoginId())) {
            throw new IllegalArgumentException("관리자 계정은 일반 비밀번호 재설정을 사용할 수 없습니다.");
        }

        user.setPassword(
                encoder.encode(newPassword)
        );

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