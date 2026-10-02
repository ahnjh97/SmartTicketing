import useBookingPage from '../booking/useBookingPage.js';
import {TheaterBooking, MovieBooking, MovieCatalog} from '../booking/BookingViews.jsx';
import styles from './BookingPage.module.css';
import ui from '../booking/BookingComponents.module.css';
import {Navigate} from 'react-router-dom';
import {QueryStatus} from '../booking/BookingComponents.jsx';
import MovieHero from '../booking/MovieHero.jsx';
import { lazy, Suspense } from 'react';
const ManualBooking = lazy(() => import('../booking/ManualBooking.jsx'));
const SmartBooking = lazy(() => import('../booking/SmartBooking.jsx'));

export default function BookingPage({mode}) {
    const booking = useBookingPage(mode);
    // Direct navigation opens the first catalog movie; home/deep-link IDs take precedence.
    if (!booking.theaterMode && !booking.params.has('movie') && booking.params.get('view') !== 'list' && booking.list.data?.items.length) {
        const next = new URLSearchParams(booking.params);
        next.set('movie', String(booking.list.data.items[0].id));
        return <Navigate to={`/movies?${next}`} replace/>;
    }
    return <div className={styles.scene}>
        {!booking.theaterMode && booking.movieId && !booking.entryValid && (booking.detail.data
            ? <MovieHero key={booking.detail.data.id} movie={booking.detail.data}/>
            : <div className={styles.container}><QueryStatus query={booking.detail}/></div>)}
        <div className={styles.container + ' ' + ui.surface}>
            <h1 className={ui.srOnly}>{booking.theaterMode ? '극장별 예매' : '영화별 예매'}</h1>
            {booking.entry === 'THEATER_NORMAL' && booking.theaterMode ? <Suspense fallback={<p role="status">예매 화면을 불러오는 중…</p>}><ManualBooking booking={booking}/></Suspense>
                : booking.entryValid ? <Suspense fallback={<p role="status">스마트예매 화면을 불러오는 중…</p>}><SmartBooking booking={booking}/></Suspense>
                : booking.theaterMode ? <TheaterBooking booking={booking}/>
                    : booking.movieId ? <MovieBooking booking={booking}/>
                        : booking.params.get('view') === 'list' ? <MovieCatalog booking={booking}/>
                            : <QueryStatus query={booking.list} empty={booking.list.data?.items.length === 0}/>}
            <footer className={styles.footer}>SmartTicketing</footer>
        </div>
    </div>;
}
