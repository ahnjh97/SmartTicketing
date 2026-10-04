import BrandLogo from './BrandLogo.jsx';
import styles from './BrandHeading.module.css';

export default function BrandHeading() {
    return (
        <div className={styles.brand}>
            <BrandLogo large decorative />
            <h1 className={styles.title}>Smart Ticketing</h1>
        </div>
    );
}
