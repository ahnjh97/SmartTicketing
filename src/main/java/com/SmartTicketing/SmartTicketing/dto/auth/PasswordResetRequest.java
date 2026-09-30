package com.SmartTicketing.SmartTicketing.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank(message = "아이디 또는 이메일은 필수입니다.")
        @Size(max = 255, message = "아이디 또는 이메일은 255자 이하로 입력해주세요.")
        String identifier,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "비밀번호는 8~100자로 입력해주세요.")
        String newPassword
) {
}
