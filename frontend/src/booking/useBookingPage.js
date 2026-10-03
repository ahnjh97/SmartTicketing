import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import useAuth from '../hooks/useAuth.js';
import useCatalog from './useCatalog.js';
import { dates, positive, rememberBooking, seoulDate, validRange, validParty, futureRange, halfHour } from './state.js';

export default function useBookingPage(mode) {
    const theaterMode = mode === 'theater';
    const { user } = useAuth();
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
    const party = params.get('party') || '';
    const rangeValid = validRange(from, until) && ((!from && !until) || (halfHour(from) && halfHour(until)));
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
    }), [setParams]);
    const selectTheater = useCallback(id => update({ theater: id, brand: null, q: null, page: 0, movie: null, showtime: null }), [update]);
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
    const brand = (theaterId && brands.includes(theater.data?.brand) ? theater.data.brand : null)
        || (brands.includes(params.get('brand')) ? params.get('brand') : 'CGV');
    const selectBrand = value => update({ brand: value, theater: null, movie: null, showtime: null, q: null, page: 0 });
    const catalogList = useCatalog(theaterMode ? (theaterId && theater.loading ? null : 'theaters') : 'movies', { page, size: 20, ...(theaterMode ? { query: search, brand } : { landscapeOnly: true }) });
    const list = !theaterMode && catalogList.data ? { ...catalogList, data: { ...catalogList.data,
        items: catalogList.data.items.filter(movie => movie.backdropUrl?.trim()) } } : catalogList;
    const movies = useCatalog(theaterMode && theaterId && dateValid ? `theaters/${theaterId}/movies` : null, { date });
    const detail = useCatalog(!theaterMode && movieId ? `movies/${movieId}` : null);
    const selectedMovieExists = theaterMode ? movies.data?.items.some(m => String(m.movieId) === movieId) : Boolean(detail.data);
    const shows = useCatalog(movieId && dateValid && selectedMovieExists && (theaterMode ? theater.data : rangeValid) ? 'showtimes' : null,
        { movieId, ...(theaterMode ? { theaterId } : from && until ? { startFrom: from, startUntil: until } : {}), date }, entry || '');
    const items = (shows.data?.items || []).filter(show => Date.parse(show.startTime) > now);
    const rangeFuture = futureRange(date, from, until, now);
    const selectedShow = items.find(s => String(s.id) === params.get('showtime'));
    const selectedMovie = theaterMode ? movies.data?.items.find(m => String(m.movieId) === movieId) : detail.data;
    const preferences = [...(user?.preferredTheaters || [])].sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0));
    const valid = Boolean(dateValid && selectedMovieExists && !shows.loading && !shows.error && (theaterMode
        ? selectedShow
        : validParty(party) && rangeFuture));
    const smartReady = theaterMode ? Boolean(selectedShow) : true;
    const enter = kind => {
        if (!valid || (kind === 'THEATER_SMART' && !smartReady)) return;
        if (theaterMode ? Date.parse(selectedShow.startTime) <= Date.now() : !futureRange(date, from, until, Date.now())) return;
        setParams(previous => {
            const next = new URLSearchParams(previous);
            next.set('entry', kind); next.set('date', date);
            // Movie smart booking selects a time range, not a specific showtime.
            if (!theaterMode) next.delete('showtime');
            return next;
        });
    };
    return { theaterMode, user, params, expanded, setExpanded, columns, today, now, rangeFuture, movieId, theaterId, date, dateValid, from, until, party, rangeValid, page, search, entry, entryValid, update, selectTheater, brand, selectBrand, list, theater, movies, detail, shows, items, selectedShow, selectedMovie, selectedMovieExists, preferences, valid, smartReady, enter };
}
