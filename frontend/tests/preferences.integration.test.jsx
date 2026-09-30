import { StrictMode } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import ResidencePreference from "../src/components/ResidencePreference.jsx";

const user = {
    id: 1, nickname: "테스터", birthDate: "2000-01-01", address: "서울",
    preferredTheaters: [{ theaterId: 3 }, { theaterId: 1 }, { theaterId: 2 }],
    preferredSeats: [
        { position: "MIDDLE_MIDDLE", priority: 1 },
        { position: "MIDDLE_MIDDLE", priority: 2 },
        { position: "SIDE_FRONT", priority: 3 },
    ],
};

beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_MAP_JS_KEY", "test-key");
    vi.stubGlobal("kakao", {
        maps: {
            load: vi.fn((callback) => callback()),
            LatLng: class { constructor(latitude, longitude) { this.latitude = latitude; this.longitude = longitude; } },
            Map: vi.fn(class {}),
        },
    });
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

test("saved seat priorities restore immediately and remain editable", () => {
    const { container } = render(
        <StrictMode>
            <ResidencePreference
                key={user.id}
                user={user}
                onSaved={vi.fn()}
            />
        </StrictMode>
    );

    expect(
        [...container.querySelectorAll(".seat-preference-region.selected")]
            .map((seat) => seat.querySelector("span").textContent + " " + seat.querySelector("strong").textContent)
    ).toEqual([
        "중간 가운데",
        "사이드 앞",
    ]);

    expect(
        container.querySelectorAll(".seat-preference-region.selected")
    ).toHaveLength(2);

    expect(window.kakao.maps.Map).toHaveBeenCalledTimes(1);
    expect(screen.getByText("서울")).toBeTruthy();

    fireEvent.click(
        screen.getByRole("button", {
            name: /중간 가운데/,
        })
    );

    expect(
        container.querySelectorAll(".seat-preference-region.selected")
    ).toHaveLength(1);

    fireEvent.click(
        screen.getByRole("button", {
            name: /중간 뒤/,
        })
    );

    fireEvent.click(
        screen.getByRole("button", {
            name: /사이드 가운데/,
        })
    );

    expect(
        container.querySelectorAll(".seat-preference-region.selected")
    ).toHaveLength(3);
});

test("a different member starts with that member's address and preferences", () => {
    const { container, rerender } = render(<ResidencePreference key={user.id} user={user} onSaved={vi.fn()} />);
    const nextUser = { ...user, id: 2, address: "부산", preferredTheaters: [], preferredSeats: [] };
    rerender(<ResidencePreference key={nextUser.id} user={nextUser} onSaved={vi.fn()} />);
    expect(
        container.querySelectorAll(".seat-preference-region.selected")
    ).toHaveLength(0);
    expect(screen.queryByText("서울")).toBe(null);
    expect(screen.getByText("부산")).toBeTruthy();
});
