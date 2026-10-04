import { audienceCounts, isYouthMember } from './audience.js';
import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import useAuth from '../hooks/useAuth.js';
import useCatalog from './useCatalog.js';
import useTheaterCatalog from './useTheaterCatalog.js';
import { dates, positive, rememberBooking, seoulDate, validRange, validParty, futureRange, halfHour } from './state.js';

export default function useBookingPage(mode) {
    const theaterMode = mode === 'theater';
    const { user, loading: authLoading } = useAuth();
    const [params, setParams] = useSearchParams();
    const [expanded, setExpanded] = useState(false);
    const [columns, setColumns] = useState(() => window.innerWidth < 600 ? 2 : window.innerWidth < 1000 ? 3 : 6);
    const [now, setNow] = useState(Date.now);
    const today = seoulDate(new Date(now));
    const movieId = positive(params.get('movie')) ? params.get('movie') : null;
    const theaterId = positive(params.get('theater')) ? params.get('theater') : null;
    const date = params.get('date') || today;
    const dateValid = dates(today).includes(date);
    const from = params.get('from') || '';
    const until = params.get('until') || '';
    const youthMember = isYouthMember(user, date);
    const { adultCount, youthCount } = audienceCounts(params, youthMember);
    const audienceValid = [adultCount, youthCount].every(n => Number.isInteger(n) && n >= 0 && n <= 6) && validParty(adultCount + youthCount);
    const party = String(adultCount + youthCount);
    const rangeValid = validRange(from, until) && halfHour(from) && halfHour(until);
    const rangeFuture = rangeValid && futureRange(date, from, until, now);
    const page = /^\d+$/.test(params.get('page') || '') ? Math.min(Number(params.get('page')), 2147483647) : 0;
    const search = params.get('q') || '';
    const entry = params.get('entry');
    const entryValid = theaterMode ? ['THEATER_NORMAL', 'THEATER_SMART'].includes(entry) : entry === 'MOVIE_SMART';
    const update = useCallback(values => setParams(previous => {
        const next = new URLSearchParams(previous);
        next.delete('entry');
        for (const [key, value] of Object.entries(values)) {
            if (value === null || value === '') next.delete(key); else next.set(key, String(value));
        }
        return next;
    }, { replace: Object.keys(values).every(key => ['date', 'from', 'until', 'adult', 'youth', 'party', 'showtime'].includes(key)) }), [setParams]);
    const selectTheater = useCallback(id => update({ theater: id, map: null, q: null, page: null, movie: null, showtime: null }), [update]);
    useEffect(() => {
        const resize = () => setColumns(window.innerWidth < 600 ? 2 : window.innerWidth < 1000 ? 3 : 6);
        const refreshClock = () => setNow(Date.now());
        const timer = setInterval(refreshClock, 1000);
        window.addEventListener('focus', refreshClock);
        document.addEventListener('visibilitychange', refreshClock);
        window.addEventListener('resize', resize);
        return () => { clearInterval(timer); window.removeEventListener('resize', resize); window.removeEventListener('focus', refreshClock); document.removeEventListener('visibilitychange', refreshClock); };
    }, []);
    useEffect(() => { rememberBooking(`${theaterMode ? '/theaters' : '/movies'}?${params}`); }, [params, theaterMode]);
    const theater = useCatalog(theaterMode && theaterId ? `theaters/${theaterId}` : null);
    const brands = ['CGV', 'LOTTE_CINEMA', 'MEGABOX'];
    const requestedBrand = params.get('brand');
    const brand = [...brands, 'FAVORITES'].includes(requestedBrand) ? requestedBrand
        : theaterId && brands.includes(theater.data?.brand) ? theater.data.brand : 'FAVORITES';
    const selectBrand = value => update({ brand: value, theater: null, movie: null, showtime: null, q: null, page: null });
    const theaterList = useTheaterCatalog(theaterMode && brand !== 'FAVORITES', brand);
    const catalogList = useCatalog(!theaterMode ? 'movies' : null, { page, size: 20, landscapeOnly: true });
    const list = theaterMode ? theaterList : catalogList.data ? { ...catalogList, data: { ...catalogList.data,
        items: catalogList.data.items.filter(movie => movie.backdropUrl?.trim()) } } : catalogList;
    const movies = useCatalog(theaterMode && theaterId && dateValid ? `theaters/${theaterId}/movies` : null, { date });
    const detail = useCatalog(!theaterMode && movieId ? `movies/${movieId}` : null);
    const selectedMovieExists = theaterMode ? movies.data?.items.some(m => String(m.movieId) === movieId) : Boolean(detail.data);
    const preferences = [...(user?.preferredTheaters || [])].sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0));
    const needsPreferences = Boolean(user && !preferences.length);
    const dayShows = useCatalog(!theaterMode && movieId && dateValid && !authLoading && !needsPreferences ? 'showtimes/availability' : null,
        { movieId, date, ...(user ? { theaterIds: preferences.map(theater => theater.theaterId).join(',') } : {}) }, `${user?.id || 'guest'}:${entry || ''}`);
    const hasDayShows = Boolean(dayShows.data?.available && Date.parse(dayShows.data.latestStartTime) > now);
    const shows = useCatalog(theaterMode && movieId && dateValid && selectedMovieExists && theater.data ? 'showtimes' : null,
        { movieId, theaterId, date }, entry || '');
    const items = (shows.data?.items || []).filter(show => Date.parse(show.startTime) > now);
    const selectedShow = items.find(s => String(s.id) === params.get('showtime'));
    const selectedMovie = theaterMode ? movies.data?.items.find(m => String(m.movieId) === movieId) : detail.data;
    const valid = Boolean(!authLoading && dateValid && selectedMovieExists && !shows.loading && !shows.error && (theaterMode
        ? selectedShow && audienceValid
        : !authLoading && !needsPreferences && hasDayShows && !dayShows.loading && !dayShows.error && audienceValid && rangeFuture));
    const smartReady = theaterMode ? Boolean(selectedShow) : true;
    const enter = kind => {
        if (!valid || (kind === 'THEATER_SMART' && !smartReady)) return;
        if (theaterMode ? Date.parse(selectedShow.startTime) <= Date.now() : !futureRange(date, from, until, Date.now())) return;
        setParams(previous => {
            const next = new URLSearchParams(previous);
            next.set('entry', kind); next.set('date', date);
            next.set('adult', String(adultCount)); next.set('youth', String(youthCount)); next.set('party', party);
            // Movie smart booking selects a time range, not a specific showtime.
            if (!theaterMode) {
                next.delete('showtime');
            }
            return next;
        });
    };
    return { youthMember, theaterMode, user, authLoading, needsPreferences, params, expanded, setExpanded, columns, today, now, rangeFuture, movieId, theaterId, date, dateValid, from, until, party, adultCount, youthCount, audienceValid, rangeValid, page, search, entry, entryValid, update, selectTheater, brand, selectBrand, list, theater, movies, detail, shows, dayShows, hasDayShows, items, selectedShow, selectedMovie, selectedMovieExists, preferences, valid, smartReady, enter };
}
