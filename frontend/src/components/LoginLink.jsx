import { Link, useLocation } from 'react-router-dom';

export default function LoginLink({ children, ...props }) {
    const location = useLocation();
    return <Link {...props} to="/login" state={{ returnTo: location.pathname + location.search }}>{children}</Link>;
}
