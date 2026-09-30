package com.SmartTicketing.SmartTicketing.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record SignupRequest(
        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자 이하로 입력해주세요.")
        String name,

        @NotNull(message = "생년월일은 필수입니다.")
        LocalDate birthDate,

        @NotBlank(message = "아이디는 필수입니다.")
        @Size(max = 255, message = "아이디는 255자 이하로 입력해주세요.")
        String loginId,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "비밀번호는 8~100자로 입력해주세요.")
        String password
) {
}