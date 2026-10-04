import styles from './BrandLogo.module.css';

export default function BrandLogo({ large = false, decorative = false }) {
    return (
        <img
            className={`${styles.logo}${large ? ` ${styles.large}` : ''}`}
            src={`${import.meta.env.BASE_URL}brand/smart-ticketing-logo.png`}
            alt={decorative ? '' : 'SmartTicketing'}
            aria-hidden={decorative || undefined}
            width="72"
            height="48"
            draggable={false}
        />
    );
}
