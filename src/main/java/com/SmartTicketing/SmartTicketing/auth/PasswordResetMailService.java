package com.SmartTicketing.SmartTicketing.auth;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetMailService {

    private final JavaMailSender mailSender;

    public PasswordResetMailService(
            JavaMailSender mailSender
    ) {
        this.mailSender = mailSender;
    }

    public void sendVerificationCode(
            String email,
            String code
    ) {
        SimpleMailMessage message =
                new SimpleMailMessage();

        message.setTo(email);
        message.setSubject(
                "[SmartTicketing] 비밀번호 재설정 인증코드"
        );
        message.setText(
                "SmartTicketing 비밀번호 재설정 인증코드입니다.\n\n"
                        + code
                        + "\n\n"
                        + "인증코드는 5분 동안 유효합니다."
        );

        mailSender.send(message);
    }
}
