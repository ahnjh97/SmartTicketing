package com.SmartTicketing.SmartTicketing.service;

import com.SmartTicketing.SmartTicketing.dto.user.*;
import com.SmartTicketing.SmartTicketing.entity.*;
import com.SmartTicketing.SmartTicketing.entity.enums.*;
import com.SmartTicketing.SmartTicketing.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class UserService {

    private final UsersRepository users;
    private final UserSocialAccountRepository social;
    private final UserPreferredTheaterRepository preferredTheaters;
    private final UserPreferredSeatRepository preferredSeats;
    private final TheaterRepository theaters;

    public UserService(
            UsersRepository u,
            UserSocialAccountRepository s,
            UserPreferredTheaterRepository t,
            UserPreferredSeatRepository ps,
            TheaterRepository tr
    ) {
        users = u;
        social = s;
        preferredTheaters = t;
        preferredSeats = ps;
        theaters = tr;
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(Long id) {
        return response(findActive(id));
    }

    @Transactional(readOnly = true)
    public PreferenceOptionResponse options() {
        var ts = theaters.findByActiveTrueOrderByNameAsc()
                .stream()
                .limit(20)
                .map(t ->
                        new PreferenceOptionResponse.TheaterOption(
                                t.getId(),
                                t.getName(),
                                t.getBrand(),
                                t.getAddress()
                        )
                )
                .toList();

        var ss = Arrays.stream(SeatPosition.values())
                .map(p ->
                        new PreferenceOptionResponse.SeatOption(
                                p,
                                label(p)
                        )
                )
                .toList();

        return new PreferenceOptionResponse(ts, ss);
    }

    public UserResponse update(Long id, UserUpdateRequest r) {
        Users u = findActive(id);

        if (r.birthDate() != null) {
            u.setBirthDate(r.birthDate());
        }

        boolean changingPreference =
                r.preferredTheaterIds() != null
                        || r.preferredSeatPositions() != null;

        if (
                changingPreference
                        && u.getBirthDate() == null
                        && r.birthDate() == null
        ) {
            throw new IllegalArgumentException(
                    "선호 영화관 또는 좌석을 설정하려면 생년월일을 입력해주세요."
            );
        }

        /*
         * 닉네임 변경
         *
         * 소셜 회원 / 일반 회원 모두 변경 가능
         */
        if (r.nickname() != null) {

            String nickname = r.nickname().trim();

            if (nickname.isBlank()) {
                throw new IllegalArgumentException(
                        "닉네임을 입력해주세요."
                );
            }

            if (nickname.length() > 100) {
                throw new IllegalArgumentException(
                        "닉네임은 100자 이하로 입력해주세요."
                );
            }

            u.setNickname(nickname);
        }

        /*
         * 일반 회원 아이디 변경
         *
         * 소셜 회원은 loginId가 없으므로 변경 불가
         */
        if (r.loginId() != null && !r.loginId().isBlank()) {

            if (u.getLoginId() == null) {
                throw new IllegalStateException(
                        "소셜 회원은 아이디를 변경할 수 없습니다."
                );
            }

            if (
                    !r.loginId().equals(u.getLoginId())
                            && users.existsByLoginIdAndIdNot(
                            r.loginId(),
                            id
                    )
            ) {
                throw new IllegalArgumentException(
                        "이미 사용 중인 아이디입니다."
                );
            }

            u.setLoginId(r.loginId());

            /*
             * 일반 회원은 닉네임 기본값이 loginId이지만,
             * 사용자가 별도로 nickname을 보냈다면
             * 위 nickname 값이 유지되어야 한다.
             *
             * 따라서 nickname이 null일 때만 loginId를 적용한다.
             */
            if (r.nickname() == null) {
                u.setNickname(r.loginId());
            }
        }

        if (r.address() != null) {
            u.setAddress(
                    r.address().isBlank()
                            ? null
                            : r.address().trim()
            );
        }

        if (r.preferredTheaterIds() != null) {
            replaceTheaters(
                    u,
                    r.preferredTheaterIds()
            );
        }

        if (r.preferredSeatPositions() != null) {
            replaceSeats(
                    u,
                    r.preferredSeatPositions()
            );
        }

        return response(u);
    }

    public void withdraw(Long id) {
        findActive(id).setStatus(UserStatus.WITHDRAWN);
    }

    private void replaceTheaters(
            Users u,
            List<Long> ids
    ) {
        var unique = new LinkedHashSet<>(ids);

        if (unique.size() < 3 || unique.size() > 5) {
            throw new IllegalArgumentException(
                    "선호 영화관은 3~5곳을 선택해주세요."
            );
        }

        var found = theaters.findAllById(unique);

        if (found.size() != unique.size()) {
            throw new IllegalArgumentException(
                    "존재하지 않는 영화관이 포함되어 있습니다."
            );
        }

        preferredTheaters.deleteAllByUserId(u.getId());

        int priority = 1;

        for (Long theaterId : unique) {

            UserPreferredTheater p =
                    new UserPreferredTheater();

            p.setUser(u);

            p.setTheater(
                    theaters.getReferenceById(theaterId)
            );

            p.setPriority(priority++);

            preferredTheaters.save(p);
        }
    }

    private void replaceSeats(
            Users u,
            List<SeatPosition> positions
    ) {
        if (positions == null ||
                positions.size() < 1 ||
                positions.size() > 6) {
            throw new IllegalArgumentException(
                    "선호 좌석은 1~6개까지 선택할 수 있습니다."
            );
        }

        preferredSeats.deleteAllByUserId(
                u.getId()
        );

        for (int i = 0; i < positions.size(); i++) {

            UserPreferredSeat p =
                    new UserPreferredSeat();

            p.setUser(u);

            p.setPriority(i + 1);

            p.setSeatPosition(
                    positions.get(i)
            );

            preferredSeats.save(p);
        }
    }

    private Users findActive(Long id) {
        Users u =
                users.findById(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "회원을 찾을 수 없습니다."
                                )
                        );

        if (u.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException(
                    "활성 상태의 회원이 아닙니다."
            );
        }

        return u;
    }

    private UserResponse response(Users u) {

        var pts =
                preferredTheaters
                        .findByUserIdOrderByPriorityAsc(u.getId())
                        .stream()
                        .map(p ->
                                new UserResponse.PreferredTheaterResponse(
                                        p.getTheater().getId(),
                                        p.getTheater().getName(),
                                        p.getTheater().getBrand().name(),
                                        p.getPriority()
                                )
                        )
                        .toList();

        var ps =
                preferredSeats
                        .findByUserId(u.getId())
                        .stream()
                        .map(UserPreferredSeat::getSeatPosition)
                        .toList();

        var providers =
                social.findByUserId(u.getId())
                        .stream()
                        .map(UserSocialAccount::getProvider)
                        .toList();

        return new UserResponse(
                u.getId(),
                u.getName(),
                u.getBirthDate(),
                u.getLoginId(),
                u.getEmail(),
                u.getNickname(),
                u.getAddress(),
                u.getStatus(),
                pts,
                ps,
                providers
        );
    }

    private String label(SeatPosition p) {
        return switch (p) {
            case SIDE_FRONT -> "좌측 · 앞";
            case SIDE_MIDDLE -> "좌측 · 가운데";
            case SIDE_REAR -> "좌측 · 뒤";
            case MIDDLE_FRONT -> "중간 · 앞";
            case MIDDLE_MIDDLE -> "중간 · 가운데";
            case MIDDLE_REAR -> "중간 · 뒤";
        };
    }
}

