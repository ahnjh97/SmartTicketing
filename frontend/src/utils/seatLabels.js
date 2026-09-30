const SEAT_POSITION_LABELS = {
    SIDE_FRONT: "좌측 · 앞",
    SIDE_MIDDLE: "좌측 · 가운데",
    SIDE_REAR: "좌측 · 뒤",
    MIDDLE_FRONT: "중간 · 앞",
    MIDDLE_MIDDLE: "중간 · 가운데",
    MIDDLE_REAR: "중간 · 뒤",
};

export function getSeatLabel(position) {
    return (
        SEAT_POSITION_LABELS[position] ??
        position
    );
}
