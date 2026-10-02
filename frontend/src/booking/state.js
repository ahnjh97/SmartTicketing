export function seoulDate(now = new Date()) {
    return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' }).format(now);
}
export function dates(today = seoulDate()) {
    return Array.from({ length: 7 }, (_, i) => {
        const date = new Date(`${today}T00:00:00+09:00`);
        date.setUTCDate(date.getUTCDate() + i);
        return seoulDate(date);
    });
}
export const positive = value => /^[1-9]\d*$/.test(String(value ?? '')) && Number.isSafeInteger(Number(value));
export const validParty = value => positive(value) && Number(value) <= 6;
export const halfHour = value => /^(?:[01]\d|2[0-3]):(?:00|30)$/.test(value);
export function validRange(from, until) {
    const time = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
    return (!from && !until) || (time.test(from) && time.test(until) && from !== until);
}
export function futureRange(date, from, until, now = Date.now()) {
    return Boolean(halfHour(from) && halfHour(until) && validRange(from, until)
        && new Date(`${date}T${from}:00+09:00`).getTime() > now);
}
export function availability(show, party) {
    if (!show.layoutComplete) return '배치 미확인';
    if (show.availableSeats === 0) return '매진';
    if (party && show.availableSeats < party) return '잔여좌석 부족';
    if (party > 1 && show.maxContiguousSeats < party) return '연속좌석 부족';
    return party ? '조회상 선택 가능' : `최대 연속 ${show.maxContiguousSeats}석 · 인원 선택 후 확인`;
}
export function rememberBooking(url) {
    if (!/^\/(movies|theaters)(\?|$)/.test(url)) return;
    try { sessionStorage.setItem('booking.return', url); } catch { /* URL remains usable without storage. */ }
}
export function bookingReturn() {
    try {
        const url = sessionStorage.getItem('booking.return');
        return /^\/(movies|theaters)(\?|$)/.test(url ?? '') ? url : null;
    } catch { return null; }
}
