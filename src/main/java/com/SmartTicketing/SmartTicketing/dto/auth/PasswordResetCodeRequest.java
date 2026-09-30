package com.SmartTicketing.SmartTicketing.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetCodeRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식으로 입력해주세요.")
        String email
) {
}
