import { useEffect, useId, useRef, useState } from 'react';
import GlassButton from './GlassButton.jsx';
import styles from './HorizontalRail.module.css';

// Native scrolling keeps touch, trackpad and focused child links working together.
export default function HorizontalRail({ children, label, className = '' }) {
    const id = useId();
    const rail = useRef(null);
    const previousButton = useRef(null);
    const nextButton = useRef(null);
    const [edges, setEdges] = useState({ previous: false, next: false });
    useEffect(() => {
        const element = rail.current;
        const update = () => {
            const previous = element.scrollLeft > 1;
            const next = element.scrollWidth - element.clientWidth - element.scrollLeft > 1;
            if ((!previous && document.activeElement === previousButton.current)
                || (!next && document.activeElement === nextButton.current)) element.focus({ preventScroll: true });
            setEdges({ previous, next });
        };
        const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
        observer?.observe(element);
        for (const child of element.children) observer?.observe(child);
        element.addEventListener('scroll', update, { passive: true });
        window.addEventListener('resize', update);
        update();
        return () => { observer?.disconnect(); element.removeEventListener('scroll', update); window.removeEventListener('resize', update); };
    }, [children]);
    function move(direction) {
        const element = rail.current;
        const card = element.firstElementChild;
        const step = card ? card.getBoundingClientRect().width + (parseFloat(getComputedStyle(element).columnGap) || 0) : element.clientWidth;
        const distance = Math.max(1, Math.floor(element.clientWidth / step)) * step;
        element.scrollBy({ left: direction * distance,
            behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
    }
    return <div className={styles.container}>
        <div id={id} ref={rail} className={`${styles.rail} ${className}`} role="region" aria-label={label} tabIndex={0}
            onKeyDown={event => {
                if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
                    event.preventDefault(); move(event.key === 'ArrowRight' ? 1 : -1);
                }
            }}>{children}</div>
        <GlassButton ref={previousButton} className={`${styles.arrow} ${styles.previous}`} aria-label={`${label} 이전`} aria-controls={id}
            disabled={!edges.previous} hidden={!edges.previous}
            onClick={() => edges.previous && move(-1)}><span aria-hidden="true">‹</span></GlassButton>
        <GlassButton ref={nextButton} className={`${styles.arrow} ${styles.next}`} aria-label={`${label} 다음`} aria-controls={id}
            disabled={!edges.next} hidden={!edges.next}
            onClick={() => edges.next && move(1)}><span aria-hidden="true">›</span></GlassButton>
    </div>;
}
