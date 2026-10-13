import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { bookingReturn, clearBookingSession } from '../booking/state.js';
import { PAGE_PATHS } from '../navigation.js';

export default function PostAuthRedirect() {
    const [destination] = useState(() => bookingReturn() || PAGE_PATHS.home);
    useEffect(() => { clearBookingSession(); }, []);
    return <Navigate to={destination} replace />;
}
