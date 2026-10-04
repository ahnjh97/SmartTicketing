import BrandHeading from './BrandHeading.jsx';

export default function AuthFormLayout({ children, error, message }) {
    return (
        <div className="page">
            <div className="card auth-card">
                <BrandHeading />
                {children}
                {error && <p className="error-message" role="alert">{error}</p>}
                {message && <p className="success-message" role="status">{message}</p>}
            </div>
        </div>
    );
}
