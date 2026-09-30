export default function AuthFormLayout({ children, error, message }) {
    return (
        <div className="page">
            <div className="card auth-card">
                <h1>SmartTicketing</h1>
                <p className="subtitle">영화 예매 시스템</p>
                {children}
                {error && <p className="error-message" role="alert">{error}</p>}
                {message && <p className="success-message" role="status">{message}</p>}
            </div>
        </div>
    );
}
