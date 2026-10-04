import BrandHeading from '../components/BrandHeading.jsx';

export default function LoadingPage() {
    return (
        <div className="page">
            <div className="card loading-card" role="status">
                <BrandHeading />
                <p>불러오는 중...</p>
            </div>
        </div>
    );
}
