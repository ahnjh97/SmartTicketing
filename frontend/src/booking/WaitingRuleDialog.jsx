import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import styles from './WaitingRuleDialog.module.css';

const rules = {
    WAITING_SINGLE_ZONE_REQUIRED: {
        title: '한 구역의 좌석을 선택해주세요',
        message: '서로 다른 구역의 좌석을 함께 대기할 수 없습니다.',
        help: '선택한 좌석을 확인하고 같은 구역 안에서 다시 선택해주세요.',
    },
};

export default function WaitingRuleDialog({ error, onClose }) {
    const [dismissed, setDismissed] = useState(null);
    const rule = rules[error?.code];
    return rule && dismissed !== error
        ? <RuleDialog rule={rule} onClose={() => { setDismissed(error); onClose?.(); }} /> : null;
}

function RuleDialog({ rule, onClose }) {
    const dialog = useRef(null);
    const titleId = useId(), descriptionId = useId();
    useEffect(() => {
        const element = dialog.current;
        const previous = document.activeElement;
        const overflow = document.body.style.overflow;
        element.showModal();
        document.body.style.overflow = 'hidden';
        return () => {
            document.body.style.overflow = overflow;
            if (previous?.isConnected) previous.focus();
        };
    }, []);
    return createPortal(<dialog ref={dialog} className={styles.dialog} aria-labelledby={titleId}
        aria-describedby={descriptionId} onCancel={event => { event.preventDefault(); onClose(); }}>
        <h2 id={titleId}>{rule.title}</h2>
        <div id={descriptionId}><p>{rule.message}</p><p className={styles.help}>{rule.help}</p></div>
        <div className={styles.actions}>
            <Link to="/bookings" onClick={onClose}>내 대기 및 선점 보기</Link>
            <button type="button" onClick={onClose} autoFocus>확인</button>
        </div>
    </dialog>, document.body);
}
