package com.SmartTicketing.SmartTicketing.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식으로 입력해주세요.")
        String email,

        @NotBlank(message = "인증코드는 필수입니다.")
        @Size(min = 6, max = 6, message = "인증코드는 6자리입니다.")
        String code,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "비밀번호는 8~100자로 입력해주세요.")
        String newPassword
) {
}
