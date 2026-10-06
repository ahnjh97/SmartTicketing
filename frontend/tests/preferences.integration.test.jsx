import { StrictMode } from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import ResidencePreference from "../src/components/ResidencePreference.jsx";
import { theaterApi } from "../src/api";

vi.mock("../src/api", () => ({ theaterApi: { nearby: vi.fn() }, userApi: {} }));

const user = {
    id: 1,
    nickname: "테스터",
    birthDate: "2000-01-01",
    address: "서울",
    preferredTheaters: [{ theaterId: 3 }, { theaterId: 1 }, { theaterId: 2 }],
    preferredSeats: [
        { position: "SIDE_FRONT", priority: 1 },
        { position: "MIDDLE_FRONT", priority: 2 },
        { position: "SIDE_MIDDLE", priority: 3 },
        { position: "MIDDLE_MIDDLE", priority: 4 },
        { position: "SIDE_REAR", priority: 5 },
        { position: "MIDDLE_REAR", priority: 6 },
    ],
};

beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_MAP_JS_KEY", "test-key");
    vi.stubGlobal("kakao", {
        maps: {
            load: vi.fn((callback) => callback()),
            LatLng: class { constructor(latitude, longitude) { this.latitude = latitude; this.longitude = longitude; } },
            Map: vi.fn(class { addControl() {} }),
            ZoomControl: vi.fn(class {}),
            ControlPosition: { RIGHT: "RIGHT" },
            event: { addListener: vi.fn(), removeListener: vi.fn() },
        },
    });
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

test.each([true, false])("map confirmation keeps a fixed pin only after successful lookup: %s", async succeeds => {
    const maps = window.kakao.maps;
    maps.LatLng = class {
        constructor(lat, lng) { this.lat = lat; this.lng = lng; }
        getLat() { return this.lat; }
        getLng() { return this.lng; }
    };
    let map;
    maps.Map = class {
        constructor(_, options) { this.center = options.center; map = this; }
        addControl() {}
        setCenter(center) { this.center = center; }
        getCenter() { return this.center; }
        setLevel() {}
    };
    maps.MarkerImage = class {};
    maps.Size = class {};
    maps.Point = class {};
    maps.Marker = vi.fn(class { setMap() {} });
    maps.services = {
        Status: { OK: "OK" },
        Geocoder: class {
            coord2Address(_, __, callback) {
                callback([{ address: { address_name: "선택한 주소" } }], "OK");
            }
        },
    };

    theaterApi.nearby.mockReset();
    if (succeeds) {
        theaterApi.nearby.mockResolvedValue(
            [1, 2, 3].map(id => ({ theaterId: id, name: `극장${id}`, distance: 100 }))
        );
    } else {
        theaterApi.nearby.mockRejectedValue(new Error("조회 실패"));
    }

    const { container } = render(<ResidencePreference user={user} onSaved={vi.fn()} />);
    expect(screen.queryByText(/다른 위치를 찾으려면/)).toBe(null);
    expect(screen.getByRole("button", { name: "선호 정보 저장" }).disabled).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "다른 위치 선택하기" }));
    map.setCenter(new maps.LatLng(37.5, 127.1));
    expect(container.querySelector(".map-center-pin")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "이 위치로 조회" }));
    await waitFor(() =>
        expect(theaterApi.nearby).toHaveBeenCalledWith(
            expect.objectContaining({ latitude: 37.5, longitude: 127.1 })
        )
    );

    if (succeeds) {
        const status = await screen.findByText("위치가 확정됐어요. 아래에서 영화관을 선택해주세요.");
        expect(status.closest("section").querySelector(".kakao-map")).toBeTruthy();
        expect(container.querySelector(".map-center-pin")).toBe(null);
        expect(maps.Marker).toHaveBeenCalledWith(
            expect.objectContaining({
                title: "조회 기준 위치",
                position: expect.objectContaining({ lat: 37.5, lng: 127.1 }),
            })
        );
        expect(screen.getByText("선택한 주소")).toBeTruthy();
        expect(screen.queryByText("확정된 위치")).toBe(null);

        const save = screen.getByRole("button", { name: "선호 정보 저장" });

        // 위치를 새로 조회하면 주변 영화관 선택은 초기화된다.
        // 따라서 조회 성공 직후에는 3곳을 다시 선택해야 저장할 수 있다.
        expect(save.disabled).toBe(true);

        fireEvent.click(screen.getByRole("button", { name: "극장1" }));
        fireEvent.click(screen.getByRole("button", { name: "극장2" }));
        fireEvent.click(screen.getByRole("button", { name: "극장3" }));
        expect(save.disabled).toBe(false);

        // 3개 → 2개
        fireEvent.click(screen.getByRole("button", { name: "극장1" }));
        expect(save.disabled).toBe(true);

        // 2개 → 3개
        fireEvent.click(screen.getByRole("button", { name: "극장1" }));
        expect(save.disabled).toBe(false);

        // 6개 → 5개
        fireEvent.click(screen.getByRole("button", { name: "중앙 5번" }));
        expect(save.disabled).toBe(true);

        // 5개 → 6개
        fireEvent.click(screen.getByRole("button", { name: "중앙 5번" }));
        expect(save.disabled).toBe(false);

        // 위치 선택 모드에서는 저장할 수 없다.
        fireEvent.click(screen.getByRole("button", { name: "다른 위치 선택하기" }));
        expect(save.disabled).toBe(true);
    } else {
        await screen.findByText("조회 실패");
        expect(screen.queryByText("위치가 확정됐어요. 아래에서 영화관을 선택해주세요.")).toBe(null);
        expect(container.querySelector(".map-center-pin")).toBeTruthy();
        expect(maps.Marker).not.toHaveBeenCalled();
    }
});

test("saved seat priorities restore immediately and remain editable", () => {
    const { container, unmount } = render(
        <StrictMode>
            <ResidencePreference key={user.id} user={user} onSaved={vi.fn()} />
        </StrictMode>
    );

    const getSelectedPositions = () =>
        [...container.querySelectorAll(".seat-zone-button.selected")]
            .map(button => button.getAttribute("aria-label"))
            .filter((position, index, positions) => positions.indexOf(position) === index);

    expect(getSelectedPositions()).toHaveLength(6);
    expect(new Set(getSelectedPositions())).toEqual(
        new Set([
            "양옆 1번",
            "중앙 4번",
            "양옆 2번",
            "중앙 5번",
            "양옆 3번",
            "중앙 6번",
        ])
    );
    expect(window.kakao.maps.Map).toHaveBeenCalledTimes(1);
    expect(screen.getByText("서울")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "중앙 5번" }));
    expect(getSelectedPositions()).toHaveLength(5);

    fireEvent.click(screen.getByRole("button", { name: "중앙 5번" }));
    expect(getSelectedPositions()).toHaveLength(6);

    unmount();
    expect(window.kakao.maps.event.addListener).toHaveBeenCalledTimes(2);
    expect(window.kakao.maps.event.removeListener.mock.calls).toEqual(
        window.kakao.maps.event.addListener.mock.calls
    );
});

test("a different member starts with that member's address and preferences", () => {
    const { container, rerender } = render(
        <ResidencePreference key={user.id} user={user} onSaved={vi.fn()} />
    );
    const nextUser = {
        ...user,
        id: 2,
        address: "부산",
        preferredTheaters: [],
        preferredSeats: [],
    };

    rerender(
        <ResidencePreference key={nextUser.id} user={nextUser} onSaved={vi.fn()} />
    );

    expect([...container.querySelectorAll(".seat-zone-button.selected")]).toHaveLength(0);
    expect(screen.queryByText("서울")).toBe(null);
    expect(screen.getByText("부산")).toBeTruthy();
});

test("same side number reacts on both left and right, while center zones stay independent", () => {
    const { container } = render(
        <ResidencePreference
            user={{ ...user, preferredSeats: [] }}
            onSaved={vi.fn()}
        />
    );

    const sideOneButtons = [
        ...container.querySelectorAll('.seat-zone-button[aria-label="양옆 1번"]'),
    ];
    const centerFourButtons = [
        ...container.querySelectorAll('.seat-zone-button[aria-label="중앙 4번"]'),
    ];

    expect(sideOneButtons).toHaveLength(2);
    expect(centerFourButtons).toHaveLength(1);

    fireEvent.mouseEnter(sideOneButtons[0]);
    expect(sideOneButtons.every(button => button.classList.contains("hovered"))).toBe(true);
    expect(centerFourButtons[0].classList.contains("hovered")).toBe(false);

    fireEvent.mouseLeave(sideOneButtons[0]);
    expect(sideOneButtons.some(button => button.classList.contains("hovered"))).toBe(false);

    fireEvent.mouseEnter(centerFourButtons[0]);
    expect(centerFourButtons[0].classList.contains("hovered")).toBe(true);
    expect(sideOneButtons.some(button => button.classList.contains("hovered"))).toBe(false);
});
