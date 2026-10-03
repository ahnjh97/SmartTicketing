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
            Map: vi.fn(class { addControl() {} }),
            ZoomControl: vi.fn(class {}),
            ControlPosition: { RIGHT: "RIGHT" },
            event: {
                addListener: vi.fn(),
                removeListener: vi.fn(),
            },
        },
    });
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

test("saved seat priorities restore immediately and remain editable", () => {
    const { container, unmount } = render(
        <StrictMode>
            <ResidencePreference
                key={user.id}
                user={user}
                onSaved={vi.fn()}
            />
        </StrictMode>
    );

    const getSelectedPositions = () =>
        [
            ...container.querySelectorAll(
                ".seat-zone-button.selected"
            ),
        ]
            .map((button) => button.getAttribute("aria-label"))
            .filter(
                (position, index, positions) =>
                    positions.indexOf(position) === index
            );

    expect(getSelectedPositions()).toEqual([
        "양옆 1번",
        "중앙 5번",
    ]);

    expect(getSelectedPositions()).toHaveLength(2);

    expect(window.kakao.maps.Map).toHaveBeenCalledTimes(1);
    expect(screen.getByText("서울")).toBeTruthy();

    fireEvent.click(
        screen.getByRole("button", {
            name: /중앙 5번/,
        })
    );

    expect(getSelectedPositions()).toHaveLength(1);

    fireEvent.click(
        screen.getByRole("button", {
            name: /중앙 6번/,
        })
    );

    fireEvent.click(
        screen.getAllByRole("button", {
            name: /양옆 2번/,
        })[0]
    );

    expect(getSelectedPositions()).toHaveLength(3);

    // StrictMode re-runs effects; each drag listener must be removed by identity.
    unmount();
    expect(window.kakao.maps.event.addListener).toHaveBeenCalledTimes(2);
    expect(window.kakao.maps.event.removeListener.mock.calls).toEqual(
        window.kakao.maps.event.addListener.mock.calls
    );
});

test("a different member starts with that member's address and preferences", () => {
    const { container, rerender } = render(<ResidencePreference key={user.id} user={user} onSaved={vi.fn()} />);
    const nextUser = { ...user, id: 2, address: "부산", preferredTheaters: [], preferredSeats: [] };
    rerender(<ResidencePreference key={nextUser.id} user={nextUser} onSaved={vi.fn()} />);
    expect(
        [
            ...container.querySelectorAll(
                ".seat-zone-button.selected"
            ),
        ]
    ).toHaveLength(0);
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
        ...container.querySelectorAll(
            '.seat-zone-button[aria-label="양옆 1번"]'
        ),
    ];
    const centerFourButtons = [
        ...container.querySelectorAll(
            '.seat-zone-button[aria-label="중앙 4번"]'
        ),
    ];

    expect(sideOneButtons).toHaveLength(2);
    expect(centerFourButtons).toHaveLength(1);

    fireEvent.mouseEnter(sideOneButtons[0]);

    expect(
        sideOneButtons.every((button) =>
            button.classList.contains("hovered")
        )
    ).toBe(true);
    expect(
        centerFourButtons[0].classList.contains("hovered")
    ).toBe(false);

    fireEvent.mouseLeave(sideOneButtons[0]);

    expect(
        sideOneButtons.some((button) =>
            button.classList.contains("hovered")
        )
    ).toBe(false);

    fireEvent.mouseEnter(centerFourButtons[0]);

    expect(
        centerFourButtons[0].classList.contains("hovered")
    ).toBe(true);
    expect(
        sideOneButtons.some((button) =>
            button.classList.contains("hovered")
        )
    ).toBe(false);
});
