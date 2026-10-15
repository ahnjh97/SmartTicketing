package smartticketing.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class VerificationMailer {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public VerificationMailer(ObjectProvider<JavaMailSender> senders,
                              @Value("${app.mail.from:}") String from) {
        this.senders = senders;
        this.from = from;
    }

    public void send(String email, String code, EmailPurpose purpose) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) {
            throw new IllegalStateException("메일 발송 설정이 준비되지 않았습니다. 잠시 후 다시 시도해주세요.");
        }
        String label = switch (purpose) {
            case SIGNUP -> "회원가입";
            case FIND_ID -> "아이디 찾기";
            case RESET_PASSWORD -> "비밀번호 재설정";
        };
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("[SmartTicketing] " + label + " 인증번호");
        message.setText(label + " 인증번호: " + code + "\n10분 안에 입력해주세요.\n본인이 요청하지 않았다면 무시해주세요.");
        try {
            sender.send(message);
        } catch (org.springframework.mail.MailException e) {
            throw new IllegalStateException("인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해주세요.");
        }
    }
}
