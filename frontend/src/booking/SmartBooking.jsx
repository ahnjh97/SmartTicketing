import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import GlassButton from '../components/GlassButton.jsx';
import AudienceFields from './AudienceFields.jsx';
import ReservationPanel from './ReservationPanel.jsx';
import { QueryStatus } from './BookingComponents.jsx';
import useManualHold from './useManualHold.js';
import { validParty } from './state.js';
import { getSeatLabel } from '../utils/seatLabels.js';
import styles from './SmartBooking.module.css';
import WaitingPanel from './WaitingPanel.jsx';
import ui from './BookingComponents.module.css';

const count = value => /^[0-6]$/.test(value ?? '') ? Number(value) : 0;
const unavailable = new Set(['NO_SHOWTIMES', 'SOLD_OUT', 'NO_CONTIGUOUS_SEATS', 'LAYOUT_UNVERIFIED', 'RETRY_EXHAUSTED']);

export default function SmartBooking({ booking }) {
    const [params, setParams] = useSearchParams();
    const flow = useManualHold({ smart: true });
    const [waiting, setWaiting] = useState(false);
    const failureHeading = useRef(null);
    useEffect(() => { if (flow.error) failureHeading.current?.focus(); }, [flow.error]);
    const movieMode = !booking.theaterMode;
    const audience = flow.group?.audience || {
        adultCount: params.has('adult') ? count(params.get('adult')) : movieMode && validParty(booking.party) ? Number(booking.party) : 1,
        youthCount: count(params.get('youth')), companionsEligible: params.get('eligible') === '1',
        guardianAccompanying: params.get('guardian') === '1',
    };
    const party = audience.adultCount + audience.youthCount;
    const preferredSeats = flow.group?.seatPreferences || [...(flow.user?.preferredSeats || [])]
        .sort((a,b) => a.priority - b.priority).map(seat => seat.seatPosition || seat.position);
    const changeAudience = values => {
        const names = { adultCount: 'adult', youthCount: 'youth', companionsEligible: 'eligible', guardianAccompanying: 'guardian' };
        setParams(previous => { const next = new URLSearchParams(previous);
            for (const [key,value] of Object.entries(values)) next.set(names[key], typeof value === 'boolean' ? value ? '1' : '0' : String(value));
            return next;
        }, { replace: true });
    };
    const back = () => {
        flow.resetIntent(); setWaiting(false);
        booking.update({ entry: null, group: null, reservation: null, eligible: null, guardian: null });
    };
    const ready = Boolean(flow.group || (booking.dateValid && booking.selectedMovie && (movieMode
        ? booking.rangeFuture && validParty(booking.party)
        : booking.selectedShow)));
    const choiceLoading = movieMode ? booking.detail.loading || booking.shows.loading
        : booking.theater.loading || booking.movies.loading || booking.shows.loading;
    const valid = ready && party >= 1 && party <= 6 && (!movieMode || flow.group || party === Number(booking.party));
    const hold = () => {
        setWaiting(false);
        flow.hold({ entryPoint: movieMode ? 'MOVIE_SMART' : 'THEATER_SMART', movieId: Number(booking.movieId),
            viewingDate: booking.date, partySize: party, audience,
            ...(movieMode ? { startTimeFrom: booking.from, startTimeTo: booking.until } : { selectedShowtimeId: booking.selectedShow?.id }) });
    };
    const choices = unavailable.has(flow.error?.code);
    const step = flow.reservation?.status === 'CONFIRMED' ? 2 : flow.reservation ? 1 : 0;
    return <div className={styles.smart}>
        <header className={styles.heading}><div><p className={styles.eyebrow}>SMART BOOKING</p><h2>좋은 자리는, 알아서.</h2>
            <p>당신의 선호순위로 찾는 {movieMode ? '극장·회차·좌석' : '나란히 앉는 좌석'}.</p></div>
            <GlassButton disabled={flow.busy} onClick={back}>선택 수정</GlassButton></header>
        <ol className={styles.steps} aria-label="스마트예매 진행 단계">{['조건 확인', '자동 선점·결제', '나의 티켓'].map((label,index) =>
            <li key={label} aria-current={index === step ? 'step' : undefined}><span>{index < step ? '✓' : `0${index+1}`}</span>{label}</li>)}</ol>
        {!flow.user ? <section className={styles.notice}><h3>로그인하고 스마트예매를 시작하세요</h3><p>선택한 영화와 조건은 그대로 돌아옵니다.</p><Link to="/login">로그인하고 이 선택으로 돌아오기 →</Link></section>
            : flow.loading ? <div className={styles.skeleton} role="status">서버에서 예매 상태를 복원하고 있습니다…</div>
            : flow.reservation ? <ReservationPanel flow={flow} onRestart={back}/>
            : flow.reservationId || flow.groupId && !flow.group ? null
            : <div className={styles.grid}>
                <section className={styles.selection} aria-label="스마트예매 조건">
                    <div className={styles.movie}><span>{movieMode ? '영화별 스마트예매' : '극장별 스마트예매'}</span><h3>{booking.selectedMovie?.title || '선택한 영화'}</h3>
                        <p>{flow.group?.viewingDate || booking.date} · {movieMode ? `${booking.from} 이상 ~ ${booking.until} 미만${booking.from > booking.until ? ' (익일)' : ''}`
                            : `${booking.theater.data?.name || '선택한 극장'} · ${booking.selectedShow?.startTime.slice(11,16) || '회차 확인 중'}`}</p></div>
                    <AudienceFields {...audience} onChange={changeAudience} disabled={flow.busy || Boolean(flow.group)}/>
                    {movieMode && party !== Number(flow.group?.partySize || booking.party) && <p className={styles.error}>선택한 총 {flow.group?.partySize || booking.party}명에 맞춰 성인·청소년 인원을 나눠주세요.</p>}
                    <p className={styles.hint}>2명 이상은 전원 같은 행·같은 통로 구간의 연속좌석만 선택합니다.</p>
                    {!ready && <><QueryStatus query={movieMode ? booking.detail : booking.shows}/>{!choiceLoading && <p role="alert">영화·날짜·시간 또는 회차를 다시 선택해주세요.</p>}</>}
                </section>
                <aside className={styles.plan} aria-label="자동 선택 기준" aria-busy={flow.busy}>
                    <div className={styles.orbit} data-searching={flow.busy} aria-hidden="true"><span>✦</span><i/><i/></div>
                    <p className={styles.eyebrow}>YOUR PREFERENCE, OUR SEARCH</p><h3>선호부터, 가능한 자리까지</h3>
                    <ol className={styles.priority}><li><b>01</b><div><strong>선호좌석 우선</strong><p>상위 구역부터, 없으면 다음 순위와 다른 구역까지</p></div></li>
                        <li><b>02</b><div><strong>{movieMode ? '저장한 선호극장 안에서' : '선택한 회차 그대로'}</strong><p>{movieMode ? '극장 선호순위, 이른 상영시간순으로 비교' : '극장과 회차를 바꾸지 않고 좌석만 선택'}</p></div></li>
                        <li><b>03</b><div><strong>전원 함께, 5분 선점</strong><p>서버에서 모두 확보한 뒤 모의결제로 이동</p></div></li></ol>
                    {preferredSeats.length > 0 && <div className={styles.preferences} aria-label="선호좌석 순위">{preferredSeats.map((seat,index) => <span key={`${seat}-${index}`}>{index+1}. {getSeatLabel(seat)}</span>)}</div>}
                    <p className={styles.hint}>{flow.group ? '이 요청은 생성 당시 선호순위를 유지합니다.' : '요청 생성 시 저장된 선호순위를 사용합니다.'} 선호좌석 배정을 보장하지 않습니다.</p>
                    <button className={`${ui.primary} ${styles.submit}`} disabled={flow.busy || !valid || !audience.companionsEligible}
                        onClick={hold}>{flow.busy ? '가능한 자리를 찾고 있습니다…' : '자동으로 찾아 5분 선점 →'}</button>
                    <p className={styles.hint} role={flow.busy ? 'status' : undefined}>{flow.busy ? '좌석을 확인 중입니다. 아직 확보가 확정되지 않았습니다.' : '실제 결제 없음 · 자동 대기 등록 없음'}</p>
                </aside>
            </div>}
        {flow.error && <section className={styles.failure} role="alert"><p className={styles.eyebrow}>CHOOSE YOUR NEXT STEP</p>
            <h3 ref={failureHeading} tabIndex={-1}>{choices ? '이번에는 자리를 확보하지 못했어요' : '요청을 완료하지 못했습니다'}</h3><p>{flow.error.message}</p>
            <div className={styles.actions}><GlassButton disabled={flow.busy} onClick={flow.refresh}>현재 예약 확인</GlassButton>
                <GlassButton disabled={flow.busy} onClick={back}>조건 변경</GlassButton>
                <Link to={`/theaters?date=${booking.date}`} onClick={flow.resetIntent}>극장·회차 직접 선택 →</Link>
                {flow.error.code === 'NO_THEATER_SCOPE' && <Link to="/preferences" onClick={flow.resetIntent}>선호극장 설정 →</Link>}
                {choices && <GlassButton aria-expanded={waiting} onClick={() => setWaiting(value => !value)}>예비번호·대기 안내</GlassButton>}</div>
            {waiting && <div className={styles.waiting}><strong>아래에서 대기할 회차를 직접 선택하세요</strong><p>신청 전에는 대기 등록이나 예비번호 발급이 이루어지지 않습니다.</p></div>}
        </section>}
        {flow.user && flow.group && <WaitingPanel key={`${flow.user.id}:${flow.group.id}`} flow={flow}/>}
    </div>;
}
