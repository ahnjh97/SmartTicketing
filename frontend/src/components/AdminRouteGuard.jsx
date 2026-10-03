import { Navigate } from 'react-router-dom';
import useAuth from '../hooks/useAuth.js';
import { PAGE_PATHS } from '../navigation.js';

export default function AdminRouteGuard({ children }) {
    const { user } = useAuth();
    return user?.admin === true ? children : <Navigate to={PAGE_PATHS.home} replace />;
}
