import styles from './FormButton.module.css';

export default function FormButton({ variant = 'primary', type = 'button', className = '', ...props }) {
    return <button type={type} className={`${styles.button} ${styles[variant]} ${className}`} {...props} />;
}
