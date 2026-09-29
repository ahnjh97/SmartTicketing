package com.SmartTicketing.SmartTicketing.dto.user;

import com.SmartTicketing.SmartTicketing.entity.enums.SeatPosition;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record UserUpdateRequest(

        @Size(
                min = 4,
                max = 50,
                message = "아이디는 4~50자로 입력해주세요."
        )
        @Pattern(
                regexp = "^[A-Za-z0-9_]+$",
                message = "아이디는 영문, 숫자, 밑줄만 사용할 수 있습니다."
        )
        String loginId,

        @Email(message = "올바른 이메일 형식을 입력해주세요.")
        @Size(
                max = 255,
                message = "이메일은 255자 이하로 입력해주세요."
        )
        String email,

        LocalDate birthDate,

        @Size(
                max = 255,
                message = "거주지는 255자 이하로 입력해주세요."
        )
        String address,

        List<Long> preferredTheaterIds,

        List<SeatPosition> preferredSeatPositions
) {
}