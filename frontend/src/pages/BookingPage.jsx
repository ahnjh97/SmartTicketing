import useBookingPage from '../booking/useBookingPage.js';
import {TheaterBooking, MovieBooking, MovieCatalog} from '../booking/BookingViews.jsx';
import styles from './BookingPage.module.css';
import ui from '../booking/BookingComponents.module.css';
import {QueryStatus} from '../booking/BookingComponents.jsx';
import MovieHero from '../booking/MovieHero.jsx';
import { lazy, Suspense } from 'react';
const ManualBooking = lazy(() => import('../booking/ManualBooking.jsx'));
const SmartBooking = lazy(() => import('../booking/SmartBooking.jsx'));

export default function BookingPage({mode}) {
    const booking = useBookingPage(mode);
    return <div className={styles.scene}>
        {!booking.theaterMode && booking.movieId && !booking.entryValid && (booking.detail.data
            ? <MovieHero key={booking.detail.data.id} movie={booking.detail.data}/>
            : <div className={styles.container}><QueryStatus query={booking.detail}/></div>)}
        <div className={styles.container + ' ' + ui.surface + (booking.theaterMode ? ' ' + styles.theaterContainer : '') + (!booking.theaterMode && booking.movieId && booking.detail.data && !booking.entryValid ? ' ' + styles.movieControls : '')}>
            <h1 className={ui.srOnly}>{booking.theaterMode ? '극장별 예매' : '영화별 예매'}</h1>
            <div className={booking.theaterMode ? styles.theaterContent : styles.content}>
            {booking.entry === 'THEATER_NORMAL' && booking.theaterMode ? <Suspense fallback={null}><ManualBooking booking={booking}/></Suspense>
                : booking.entryValid ? <Suspense fallback={null}><SmartBooking booking={booking}/></Suspense>
                : booking.theaterMode ? <TheaterBooking booking={booking}/>
                    : booking.movieId ? <MovieBooking booking={booking}/>
                        : <MovieCatalog booking={booking}/>}
            </div>
            <footer className={styles.footer}>SmartTicketing</footer>
        </div>
    </div>;
}
