import styles from './GlassButton.module.css';

// Use styles.button directly on router links to retain their native navigation semantics.
export default function GlassButton({ className = '', type = 'button', ...props }) {
    return <button type={type} className={`${styles.button} ${className}`} {...props} />;
}
