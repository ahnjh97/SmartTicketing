package smartticketing.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.EmailChallenge;
import smartticketing.repository.EmailChallengeRepository;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class EmailVerificationService {
    private final EmailChallengeRepository challenges;
    private final VerificationMailer mailer;
    private final String secret;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(EmailChallengeRepository challenges, VerificationMailer mailer,
                                    @Value("${app.jwt.secret}") String secret) {
        this.challenges = challenges;
        this.mailer = mailer;
        this.secret = secret;
    }

    public static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void limitClient(String address, boolean verificationAttempt) {
        String id = digest("client:" + verificationAttempt + ":" + address);
        EmailChallenge limit = challenges.lockById(id).orElseGet(EmailChallenge::new);
        Instant now = Instant.now();
        if (limit.getWindowStart() == null || !limit.getWindowStart().plusSeconds(3600).isAfter(now)) {
            limit.setWindowStart(now);
            limit.setSendCount(0);
        }
        if (limit.getSendCount() >= (verificationAttempt ? 100 : 20)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 나중에 다시 시도해주세요.");
        }
        limit.setId(id);
        limit.setEmail("client-rate-limit");
        limit.setSendCount(limit.getSendCount() + 1);
        challenges.saveAndFlush(limit);
    }

    @Transactional
    public void request(String email, EmailPurpose purpose) {
        email = normalize(email);
        Instant now = Instant.now();
        String id = digest(purpose + ":" + email);
        EmailChallenge c = challenges.lockById(id).orElseGet(EmailChallenge::new);
        if (c.getSentAt() != null && c.getSentAt().plusSeconds(60).isAfter(now)) {
            throw new IllegalArgumentException("인증번호는 60초 후 다시 요청해주세요.");
        }
        if (c.getWindowStart() == null || !c.getWindowStart().plusSeconds(3600).isAfter(now)) {
            c.setWindowStart(now);
            c.setSendCount(0);
        }
        if (c.getSendCount() >= 5) throw new IllegalArgumentException("요청 횟수를 초과했습니다. 나중에 다시 시도해주세요.");
        String code = String.format("%06d", random.nextInt(1_000_000));
        c.setId(id);
        c.setEmail(email);
        c.setPurpose(purpose.name());
        c.setCodeHash(digest(id + ":" + code));
        c.setGrantHash(null);
        c.setExpiresAt(now.plusSeconds(600));
        c.setAttempts(0);
        c.setSentAt(now);
        c.setSendCount(c.getSendCount() + 1);
        // Flush the unique key before sending, so concurrent initial requests cannot both send.
        challenges.saveAndFlush(c);
        mailer.send(email, code, purpose);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public String verify(String email, EmailPurpose purpose, String code) {
        EmailChallenge c = locked(email, purpose);
        if (c.getAttempts() >= 5 || c.getCodeHash() == null || expired(c)) throw invalid();
        c.setAttempts(c.getAttempts() + 1);
        if (!equal(c.getCodeHash(), digest(c.getId() + ":" + code))) throw invalid();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String grant = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        c.setGrantHash(digest(grant));
        c.setCodeHash(null);
        c.setExpiresAt(Instant.now().plusSeconds(600));
        return grant;
    }

    // Joins signup/reset transaction: a grant is consumed only with the successful account operation.
    @Transactional
    public void consume(String email, EmailPurpose purpose, String grant) {
        EmailChallenge c = locked(email, purpose);
        if (grant == null || c.getGrantHash() == null || expired(c)
                || !equal(c.getGrantHash(), digest(grant))) throw invalid();
        c.setGrantHash(null);
    }

    private EmailChallenge locked(String email, EmailPurpose purpose) {
        return challenges.lockById(digest(purpose + ":" + normalize(email))).orElseThrow(this::invalid);
    }
    private boolean expired(EmailChallenge c) { return !c.getExpiresAt().isAfter(Instant.now()); }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("인증 정보가 올바르지 않거나 만료되었습니다. 인증번호를 다시 요청해주세요."); }
    private boolean equal(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
