const SEAT_POSITION_LABELS = {
    SIDE_FRONT: "양옆 · 앞",
    SIDE_MIDDLE: "양옆 · 가운데",
    SIDE_REAR: "양옆 · 뒤",
    MIDDLE_FRONT: "중앙 · 앞",
    MIDDLE_MIDDLE: "중앙 · 가운데",
    MIDDLE_REAR: "중앙 · 뒤",
};

export function getSeatLabel(position) {
    return (
        SEAT_POSITION_LABELS[position] ??
        position
    );
}
