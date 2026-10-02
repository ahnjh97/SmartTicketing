import styles from './ManualBooking.module.css';

export default function AudienceFields({ adultCount, youthCount, companionsEligible, guardianAccompanying, onChange, disabled }) {
    return <fieldset className={styles.audience} disabled={disabled}>
        <legend>01 <span>함께 볼 인원</span></legend>
        <div className={styles.counts}>{[['adultCount', '성인', adultCount, '10,000원'], ['youthCount', '청소년', youthCount, '8,000원']].map(([field, label, count, price]) =>
            <label key={field}>{label}<small>{price}</small><select aria-label={`${label} 인원`} value={count} onChange={e => onChange({ [field]: Number(e.target.value) })}>
                {[0,1,2,3,4,5,6].map(value => <option key={value} value={value}>{value}명</option>)}
            </select></label>)}</div>
        <p className={styles.hint}>본인을 포함해 1~6명. 청소년은 상영 연도에 만 19세가 되지 않는 관객입니다.</p>
        <label className={styles.check}><input type="checkbox" checked={companionsEligible} onChange={e => onChange({ companionsEligible: e.target.checked })} />
            동반 관객 모두 관람등급을 충족하거나, 12세 또는 15세 관람등급의 기준 나이보다 어린 관객이 실제 보호자와 동반함을 확인합니다.</label>
        <label className={styles.check}><input type="checkbox" checked={guardianAccompanying} onChange={e => onChange({ guardianAccompanying: e.target.checked })} />
            12세 또는 15세 관람등급의 기준 나이보다 어린 관객의 부모 등 보호자가 실제 동반합니다. (해당 시 선택)</label>
        <p className={styles.hint}>예매자 생년월일은 회원정보로 확인합니다. 청소년관람불가에는 보호자 동반 예외가 없습니다.</p>
    </fieldset>;
}
