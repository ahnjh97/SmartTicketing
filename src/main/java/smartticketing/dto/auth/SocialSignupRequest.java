package smartticketing.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record SocialSignupRequest(
        @NotBlank(message = "아이디는 필수입니다.")
        @Email(message = "올바른 이메일 형식으로 입력해주세요.")
        @Size(max = 255, message = "아이디는 255자 이하로 입력해주세요.")
        String loginId,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "비밀번호는 8~100자로 입력해주세요.")
        String password,

        @NotNull(message = "생년월일은 필수입니다.")
        LocalDate birthDate
) {
}