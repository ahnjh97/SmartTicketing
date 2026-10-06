import { useEffect, useMemo } from 'react';
import { cinemaTime, halfHour } from './state.js';
import { defaultTimeRange, minuteOf, timeOptions } from './timeRange.js';

export default function useMovieTimeDefaults({ enabled, userId, movieId, date, now, runningTime, schedule, params }) {
    const key = `booking.time-range:${userId || 'guest'}:${movieId}:${date}`;
    const saved = useMemo(() => {
        try { return JSON.parse(sessionStorage.getItem(key)); } catch { return null; }
    }, [key]);
    const defaults = enabled ? defaultTimeRange(date, now, runningTime, schedule) : { from: '', until: '' };
    const source = params.has('from') || params.has('until') || !enabled ? { from: params.get('from'), until: params.get('until') }
        : saved && halfHour(saved.from) && halfHour(saved.until) ? saved : defaults;
    let from = source.from || defaults.from;
    let until = source.until || defaults.until;
    // Derive the safe display value without a background navigation that could overwrite a user's click.
    if (enabled && halfHour(from) && cinemaTime(date, from) <= now) {
        from = timeOptions(date, now, runningTime).starts[0]?.time || '';
        if (!from) until = '';
        else if (minuteOf(until) <= minuteOf(from)) until = defaults.until;
    }
    useEffect(() => {
        if (enabled && halfHour(from) && halfHour(until) && minuteOf(until) > minuteOf(from)) {
            try { sessionStorage.setItem(key, JSON.stringify({ from, until })); } catch { /* Retain URL state. */ }
        }
    }, [enabled, key, from, until]);
    return { from, until };
}
