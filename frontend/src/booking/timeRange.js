import { cinemaTime, halfHour } from './state.js';

export const timeLabel = minute => String(Math.floor(minute / 60) % 24).padStart(2, '0') + ':' + String(minute % 60).padStart(2, '0');
export const minuteOf = time => halfHour(time) ? Number(time.slice(0, 2)) * 60 + Number(time.slice(3)) + (time < '04:00' ? 1440 : 0) : null;

export function timeOptions(date, now, runningTime) {
    // The schedule starts at 08:00 and finishes by 03:00, including advertisements.
    const latestStart = 27 * 60 - Math.max(0, Number(runningTime) || 0) - 10;
    const upperBound = (Math.floor(latestStart / 30) + 1) * 30;
    const slots = Array.from({ length: Math.max(0, (upperBound - 480) / 30 + 1) }, (_, i) => ({ minute: 480 + i * 30, time: timeLabel(480 + i * 30) }));
    return { upperBound, slots, starts: slots.filter(slot => slot.minute < upperBound && slot.minute <= latestStart && cinemaTime(date, slot.time) > now) };
}

export function defaultTimeRange(date, now, runningTime, schedule = {}) {
    const { starts, upperBound } = timeOptions(date, now, runningTime);
    const base = new Date(`${date}T00:00:00+09:00`).getTime();
    const earliest = (Date.parse(schedule?.earliestStartTime) - base) / 60000;
    const latest = (Date.parse(schedule?.latestStartTime) - base) / 60000;
    const from = starts.find(slot => !Number.isFinite(earliest) || slot.minute >= Math.floor(earliest / 30) * 30) || starts[0];
    if (!from) return { from: '', until: '' };
    const end = Number.isFinite(latest) ? Math.ceil(latest / 30) * 30 : upperBound;
    return { from: from.time, until: timeLabel(Math.min(upperBound, Math.max(from.minute + 30, end))) };
}
