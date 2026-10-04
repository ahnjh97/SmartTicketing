import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import BookingMap from './BookingMap.jsx';
import useTheaterCatalog from './useTheaterCatalog.js';
import { QueryStatus } from './BookingComponents.jsx';
import styles from './BookingMap.module.css';

export default function BookingMapDialog({ onClose, onSelect }) {
    const dialog = useRef(null);
    const theaters = useTheaterCatalog(true);
    useEffect(() => {
        const previous = document.activeElement;
        const overflow = document.body.style.overflow;
        dialog.current.showModal();
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = overflow; previous?.focus(); };
    }, []);
    return createPortal(<dialog ref={dialog} className={styles.dialog} aria-label="지도에서 극장 선택"
        onCancel={event => { event.preventDefault(); onClose(); }}
        onClick={event => { if (event.target === dialog.current) {
            const rect = dialog.current.getBoundingClientRect();
            if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose();
        } }}>
        <header className={styles.dialogHeading}><h2>지도에서 극장 선택</h2><button type="button" aria-label="지도 닫기" onClick={onClose}>×</button></header>
        <QueryStatus query={theaters} />
        <BookingMap theaters={theaters.data?.items || []} onSelect={onSelect} />
    </dialog>, document.body);
}
