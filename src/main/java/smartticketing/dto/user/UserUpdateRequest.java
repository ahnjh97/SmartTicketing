package smartticketing.dto.user;

import smartticketing.entity.enums.SeatPosition;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record UserUpdateRequest(

        @Email(
                message = "올바른 이메일 형식으로 입력해주세요."
        )
        @Size(
                max = 255,
                message = "아이디는 255자 이하로 입력해주세요."
        )
        String loginId,

        @Size(
                min = 1,
                max = 100,
                message = "닉네임은 1~100자로 입력해주세요."
        )
        String nickname,

        LocalDate birthDate,

        @Size(
                max = 255,
                message = "거주지는 255자 이하로 입력해주세요."
        )
        String address,

        List<Long> preferredTheaterIds,

        @Size(
                min = 6,
                max = 6,
                message = "선호 좌석 6개를 모두 선택해주세요."
        )
        List<SeatPosition> preferredSeatPositions
) {
}