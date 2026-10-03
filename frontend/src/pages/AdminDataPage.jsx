import { useEffect, useId, useRef, useState } from 'react';
import { adminApi } from '../api/admin.js';
import './AdminDataPage.css';

const NAMES = {
    movies: '영화', theaters: '영화관', screens: '상영관', showtimes: '상영회차', seats: '좌석 배치', showtime_seats: '회차별 좌석',
    reservations: '예약', reservation_seats: '예약 좌석', payments: '결제', tickets: '티켓', waiting_queues: '대기열',
    queue_counters: '대기 순번', notifications: '관련 알림', booking_operations: '관련 회원의 요청 재시도 기록',
    booking_group_holds: '그룹 선점', booking_request_groups: '예매 요청', booking_group_seat_preferences: '요청 좌석 선호',
    booking_group_theater_preferences: '요청 영화관 선호', user_preferred_theaters: '회원 선호 영화관', user_nearby_theaters: '회원 주변 영화관',
    detached_booking_groups: '선택 회차 연결 해제',
};
const STATUS = { AVAILABLE: '선택 가능', HOLDING: '선점 중', RESERVED: '예약 완료', BLOCKED: '차단', MISSING: '재고 미생성' };
const SHOW_STATUS = { SCHEDULED: '상영 예정', CLOSED: '접수 마감', CANCELLED: '취소', COMPLETED: '상영 완료' };
function collectionResult(result) {
    const labels = { savedCount: '추가', insertedCount: '추가', updatedCount: '수정', skippedCount: '생략', skippedQueryCount: '생략한 수집', apiCallCount: '외부 조회', showtimes: '생성 회차', screens: '생성 상영관', showtime_seats: '생성 좌석 재고' };
    const parts = Object.entries(labels).filter(([key]) => result?.[key] !== undefined).map(([key, name]) => `${name} ${Number(result[key]).toLocaleString()}건`);
    if (result?.failedIds?.length) parts.push(`수집 실패 ${result.failedIds.length}건 (영화 ID: ${result.failedIds.join(', ')})`);
    return parts.join(' · ');
}
const dateTime = value => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '—';
const enabled = value => value === true || value === 1;

export default function AdminDataPage() {
    return <div className="admin-data">
        <header className="admin-data-heading"><h1>데이터 관리</h1></header>
        <DataConsole />
    </div>;
}

function DataConsole() {
    const [kind, setKind] = useState('showtimes');
    const [browse, setBrowse] = useState({ theaters: [], movies: [], dates: [] });
    const [applied, setApplied] = useState({});
    const [page, setPage] = useState(0);
    const [revision, setRevision] = useState(0);
    const [data, setData] = useState({ items: [], total: 0 });
    const [summary, setSummary] = useState({});
    const [selected, setSelected] = useState([]);
    const [loading, setLoading] = useState(true);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [preview, setPreview] = useState(null);
    const [confirmation, setConfirmation] = useState('');
    const [includeBookings, setIncludeBookings] = useState(false);
    const [seatShow, setSeatShow] = useState(null);
    const [editing, setEditing] = useState(null);
    const [refreshSource, setRefreshSource] = useState(false);

    useEffect(() => {
        const controller = new AbortController();
        Promise.all([adminApi.list(kind, { ...applied, page }, controller.signal), adminApi.summary(controller.signal)])
            .then(([result, totals]) => { if (!controller.signal.aborted) { setData(result); setSummary(totals); } })
            .catch(e => { if (!controller.signal.aborted) { setError(e.message); setData({ items: [], total: 0 }); } })
            .finally(() => { if (!controller.signal.aborted) setLoading(false); });
        return () => controller.abort();
    }, [kind, applied, page, revision]);

    function startLoad() { setLoading(true); setError(''); setSelected([]); setPreview(null); setSeatShow(null); setEditing(null); }
    function reload() { startLoad(); setRevision(x => x + 1); }

    async function run(action) {
        setBusy(true); setError(''); setNotice('');
        try { await action(); } catch (e) { setError(e.message); } finally { setBusy(false); }
    }
    function changeKind(next) {
        startLoad();
        setKind(next); setPage(0); setApplied({}); setPreview(null);
    }
    function choose(next) { startLoad(); setApplied(next); setPage(0); }
    useEffect(() => {
        const controller = new AbortController();
        adminApi.browse({ theaterId: applied.theaterId, movieId: applied.movieId }, controller.signal)
            .then(result => { if (!controller.signal.aborted) setBrowse(result); })
            .catch(e => { if (!controller.signal.aborted) setError(e.message); });
        return () => controller.abort();
    }, [applied.theaterId, applied.movieId, revision]);
    function prepareDelete(mode) {
        const scope = { kind, mode, ...(mode === 'selected' ? { ids: selected } : mode === 'filtered' ? applied : {}) };
        run(async () => {
            const result = await adminApi.preview(scope);
            setPreview({ ...result, scope }); setConfirmation(''); setIncludeBookings(false);
        });
    }
    function deleteData() {
        run(async () => {
            const result = await adminApi.delete({ scope: preview.scope, fingerprint: preview.fingerprint, confirmation, includeBookings });
            setNotice(`${NAMES[kind]} ${result.targetCount.toLocaleString()}개와 연결 데이터 삭제 완료`);
            setPage(0); reload();
        });
    }
    function collect(label, action) {
        run(async () => { const result = await action(); setNotice(`${label} 처리 결과 · ${collectionResult(result)}`); reload(); });
    }
    const disabled = busy || loading;
    const allChecked = data.items.length > 0 && data.items.every(item => selected.includes(item.id));

    return <>
        <div className="admin-summary">{Object.entries(summary).map(([name, count]) => <div className="admin-card" key={name}><span>{NAMES[name]}</span><strong>{count.toLocaleString()}</strong></div>)}</div>
        <section className="admin-card admin-collection">
            <h2>수집·생성</h2>
            <label className="admin-check" title="외부 데이터를 다시 조회합니다. 영화는 기존 값을 보존하고 누락된 정보를 보충합니다."><input type="checkbox" checked={refreshSource} onChange={e => setRefreshSource(e.target.checked)} disabled={busy} />전체 재조회</label>
            <div className="admin-actions">
                <CollectionButton description="TMDB에서 영화 정보와 포스터 등 누락 정보를 가져옵니다." disabled={disabled} onClick={() => collect('영화 수집', () => adminApi.collectMovies(refreshSource))}>영화 수집</CollectionButton>
                <CollectionButton description="서울의 주요 영화관을 수집하고 중복 지점을 정리합니다." disabled={disabled} onClick={() => collect('영화관 수집', () => adminApi.collectTheaters(refreshSource))}>영화관 수집</CollectionButton>
                <CollectionButton description="테스트용 상영회차와 누락된 좌석 데이터를 생성합니다. 실제 영화관 시간표를 수집하지는 않습니다." disabled={disabled} onClick={() => collect('시간표·좌석 보충', () => adminApi.prepare())}>시간표·좌석 보충</CollectionButton>
            </div>
        </section>
        {notice && <p className="admin-feedback" role="status">{notice}</p>}
        {busy && <p role="status">작업 중입니다. 수집·대량 삭제는 시간이 걸릴 수 있습니다.</p>}
        <section className="admin-card">
            <div className="admin-tabs" role="tablist" aria-label="데이터 종류">{['showtimes', 'movies', 'theaters'].map(name => <button key={name} role="tab" aria-selected={kind === name} disabled={busy} onClick={() => changeKind(name)}>{NAMES[name]}</button>)}</div>
            {kind === 'showtimes' && <div className="admin-browser">
                <div><h3>1. 영화관</h3><div className="admin-choice-list" role="group" aria-label="영화관 선택">
                    <button aria-pressed={!applied.theaterId} disabled={disabled} onClick={() => choose({})}>모든 영화관</button>
                    {browse.theaters.map(t => <button key={t.id} aria-pressed={applied.theaterId === t.id} disabled={disabled} onClick={() => choose({ theaterId: t.id })}>{t.name}</button>)}
                </div></div>
                <div><h3>2. 영화</h3><div className="admin-choice-list" role="group" aria-label="영화 선택">
                    <button aria-pressed={!applied.movieId} disabled={disabled} onClick={() => choose(applied.theaterId ? { theaterId: applied.theaterId } : {})}>모든 영화</button>
                    {browse.movies.map(m => <button key={m.id} aria-pressed={applied.movieId === m.id} disabled={disabled} onClick={() => choose({ ...(applied.theaterId ? { theaterId: applied.theaterId } : {}), movieId: m.id })}>{m.title}</button>)}
                </div></div>
                <div><h3>3. 날짜</h3><div className="admin-choice-list" role="group" aria-label="날짜 선택">
                    <button aria-pressed={!applied.date} disabled={disabled} onClick={() => choose({ ...applied, date: undefined })}>모든 날짜</button>
                    {browse.dates.map(d => <button key={d.date} aria-pressed={applied.date === d.date} disabled={disabled} onClick={() => choose({ ...applied, date: d.date })}>{d.date}</button>)}
                </div></div>
            </div>}
            <div className="admin-actions admin-refresh"><button disabled={disabled} onClick={reload}>새로고침</button></div>
            <div className="admin-table-toolbar"><span>목록 <b>{data.total.toLocaleString()}</b>개 · 선택 {selected.length}개</span><div className="admin-actions">
                <button className="admin-danger" disabled={disabled || !selected.length} onClick={() => prepareDelete('selected')}>선택 삭제</button>
                <button className="admin-danger" disabled={disabled || !data.total} onClick={() => prepareDelete('filtered')}>목록 전체 삭제</button>
                <button className="admin-danger" disabled={disabled || !summary[kind]} onClick={() => prepareDelete('all')}>{NAMES[kind]} 전부 삭제</button>
            </div></div>
            <p className="admin-hint">‘전부 삭제’는 선택한 영화관·영화·날짜와 페이지에 관계없이 해당 종류 전체가 대상입니다. 삭제 전 연결 데이터 건수를 확인할 수 있습니다.</p>
            <div className="admin-table-scroll"><table>{error && <caption className="admin-table-error" role="alert">{error}</caption>}<thead><tr><th><input type="checkbox" aria-label="현재 페이지 전체 선택" checked={allChecked} disabled={disabled} onChange={e => { setSelected(e.target.checked ? data.items.map(item => item.id) : []); setPreview(null); }} /></th><th>ID</th><th>{kind === 'showtimes' ? '영화 / 영화관' : '이름'}</th><th>정보</th><th>상태</th><th>관리</th></tr></thead>
                <tbody>{loading ? <tr><td colSpan="6">불러오는 중…</td></tr> : data.items.length === 0 ? <tr><td colSpan="6">표시할 데이터가 없습니다.</td></tr> : data.items.map(item => <tr key={item.id}>
                    <td><input type="checkbox" aria-label={`${item.id} 선택`} checked={selected.includes(item.id)} disabled={disabled} onChange={e => { setSelected(old => e.target.checked ? [...old, item.id] : old.filter(id => id !== item.id)); setPreview(null); }} /></td>
                    <td>{item.id}</td><td><strong>{item.title || item.name}</strong>{kind === 'showtimes' && <small>{item.theater_name} · {item.screen_name}</small>}</td>
                    <td>{kind === 'showtimes' ? <>{dateTime(item.start_time)}<small>{item.available_seats} / {item.total_seats}석 · {item.price_per_person?.toLocaleString() ?? '미설정'}원</small></> : kind === 'movies' ? `${item.running_time ?? '—'}분 · ${item.rating || '등급 미설정'}` : item.address}</td>
                    <td>{kind === 'showtimes' ? (SHOW_STATUS[item.status] || item.status) : enabled(item.is_active) ? '활성' : '비활성'}</td><td><div className="admin-actions">
                        {kind === 'showtimes' && <button disabled={disabled} onClick={() => { setSeatShow(item); setEditing(null); }}>좌석 현황</button>}
                        <button disabled={disabled} onClick={() => { setEditing(item); setSeatShow(null); setPreview(null); }}>수정</button>
                    </div></td>
                </tr>)}</tbody></table></div>
            <div className="admin-pagination"><button disabled={disabled || page === 0} onClick={() => { startLoad(); setPage(x => x - 1); }}>이전</button><span>{page + 1} / {Math.max(1, Math.ceil(data.total / 50))} 페이지</span><button disabled={disabled || (page + 1) * 50 >= data.total} onClick={() => { startLoad(); setPage(x => x + 1); }}>다음</button></div>
        </section>
        {preview && <section className="admin-card admin-delete-preview" aria-label="삭제 영향 확인">
            <h2>삭제 영향 확인</h2><p><strong>{NAMES[preview.scope.kind]} {preview.targetCount.toLocaleString()}개</strong> · {({ selected: '선택한 항목', filtered: '목록 전체', all: '모든 항목 (목록 선택 무시)' })[preview.scope.mode]}</p>
            <div className="admin-impact">{Object.entries(preview.counts).filter(([, n]) => n > 0).map(([name, count]) => <div key={name}><span>{NAMES[name] || name}</span><strong>{count.toLocaleString()}개</strong></div>)}</div>
            <p>삭제는 되돌릴 수 없습니다. 연결된 데이터도 함께 정리되며, 회원 계정은 유지됩니다. 관련 회원의 요청 재시도 기록은 함께 초기화됩니다.</p>
            {preview.hasBookings && <label className="admin-check"><input type="checkbox" checked={includeBookings} onChange={e => setIncludeBookings(e.target.checked)} disabled={busy} />연결된 예약·결제·티켓·대기 데이터 삭제에 동의합니다.</label>}
            <label>확인 문구 ‘삭제’ 입력<input value={confirmation} onChange={e => setConfirmation(e.target.value)} disabled={busy} autoComplete="off" /></label>
            <div className="admin-actions"><button className="admin-danger-solid" disabled={busy || preview.targetCount === 0 || confirmation !== '삭제' || (preview.hasBookings && !includeBookings)} onClick={deleteData}>영구 삭제 실행</button><button disabled={busy} onClick={() => setPreview(null)}>취소</button></div>
        </section>}
        {editing && <EditPanel key={`${kind}-${editing.id}`} item={editing} kind={kind} busy={busy} close={() => setEditing(null)} save={body => run(async () => { await adminApi.edit(kind, editing.id, body); setNotice('수정했습니다.'); reload(); })} />}
        {seatShow && <SeatPanel key={seatShow.id} show={seatShow} close={() => setSeatShow(null)} onBusy={setBusy} onChanged={() => setRevision(x => x + 1)} />}
    </>;
}

function CollectionButton({ description, children, disabled, onClick }) {
    const id = useId();
    const [open, setOpen] = useState(false);
    return <span className="admin-tooltip" onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}
        onFocus={() => setOpen(true)} onBlur={() => setOpen(false)} onKeyDown={e => { if (e.key === 'Escape') setOpen(false); }}>
        <button disabled={disabled} onClick={onClick} aria-describedby={id}>{children}</button>
        <span id={id} className="admin-tooltip-content" role="tooltip" hidden={!open}>{description}</span>
    </span>;
}

function EditPanel({ item, kind, busy, close, save }) {
    const [form, setForm] = useState({ title: item.title ?? '', name: item.name ?? '', address: item.address ?? '', runningTime: item.running_time ?? '', active: enabled(item.is_active), price: item.price_per_person ?? '' });
    const field = (name, label, type = 'text') => <label>{label}<input type={type} required value={form[name]} onChange={e => setForm({ ...form, [name]: e.target.value })} maxLength={type === 'text' ? 255 : undefined} min={name === 'runningTime' ? 1 : 0} max={name === 'runningTime' ? 1440 : name === 'price' ? 1000000 : undefined} /></label>;
    return <form className="admin-card" onSubmit={e => { e.preventDefault(); save({ ...form, runningTime: Number(form.runningTime), price: Number(form.price) }); }}><h2>{NAMES[kind]} 수정 · #{item.id}</h2>
        <fieldset disabled={busy} className="admin-edit-fields">{kind === 'movies' ? <>{field('title', '영화명')}{field('runningTime', '상영시간 (분)', 'number')}<p>상영시간 변경은 기존 회차의 시작·종료 시각을 변경하지 않습니다.</p></> : kind === 'theaters' ? <>{field('name', '영화관명')}{field('address', '주소')}</> : <>{field('price', '1인 가격 (원)', 'number')}<p>예약·대기 기록이 있는 회차는 가격을 변경할 수 없습니다.</p></>}
            {kind !== 'showtimes' && <label className="admin-check"><input type="checkbox" checked={form.active} onChange={e => setForm({ ...form, active: e.target.checked })} />활성</label>}
            <div className="admin-actions"><button className="admin-primary">저장</button><button type="button" onClick={close}>닫기</button></div></fieldset></form>;
}

function SeatPanel({ show, close, onBusy, onChanged }) {
    const panel = useRef(null);
    useEffect(() => { panel.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }); }, []);
    const [seats, setSeats] = useState([]);
    const [error, setError] = useState('');
    const [updated, setUpdated] = useState(null);
    const [revision, setRevision] = useState(0);
    const [auto, setAuto] = useState(false);
    const [picked, setPicked] = useState([]);
    const [busy, setBusy] = useState(false);
    const [preview, setPreview] = useState(null);
    const [confirmation, setConfirmation] = useState('');
    const [notice, setNotice] = useState('');
    async function prepare(action, mode) {
        setBusy(true); onBusy(true); setError(''); setNotice('');
        const scope = { showtimeId: show.id, action, mode, ...(mode === 'selected' ? { seatIds: picked } : {}) };
        try { const result = await adminApi.bookingPreview(scope); setPreview({ ...result, scope }); setConfirmation(''); }
        catch (e) { setError(e.message); }
        finally { setBusy(false); onBusy(false); }
    }
    async function execute() {
        setBusy(true); onBusy(true); setError('');
        try {
            await adminApi.bookingExecute({ scope: preview.scope, fingerprint: preview.fingerprint, confirmation });
            setNotice(preview.scope.action === 'purge' ? '예매 기록을 삭제했습니다.' : '예매를 취소했습니다.');
            setPreview(null); setPicked([]); setRevision(x => x + 1); onChanged();
        } catch (e) { setError(e.message); setPreview(null); setConfirmation(''); setRevision(x => x + 1); }
        finally { setBusy(false); onBusy(false); }
    }
    useEffect(() => {
        const controller = new AbortController();
        adminApi.seats(show.id, controller.signal).then(items => {
            if (!controller.signal.aborted) { setSeats(items); setUpdated(new Date()); }
        }).catch(e => { if (!controller.signal.aborted) setError(e.message); });
        return () => controller.abort();
    }, [show.id, revision]);
    useEffect(() => { if (!auto || busy || preview) return; const timer = setInterval(() => setRevision(x => x + 1), 5000); return () => clearInterval(timer); }, [auto, busy, preview]);
    const rows = Object.groupBy(seats, seat => seat.seat_row);
    const selectedSeat = seats.find(s => s.id === picked.at(-1));
    return <section ref={panel} className="admin-card admin-seat-panel"><div className="admin-table-toolbar"><div><h2>좌석 현황 · {show.title}</h2><p>{show.theater_name} · {show.screen_name} · {dateTime(show.start_time)}</p></div><button disabled={busy} onClick={close}>닫기</button></div>
        <div className="admin-actions"><button disabled={busy} onClick={() => { setPreview(null); setRevision(x => x + 1); }}>좌석 새로고침</button><label className="admin-check"><input type="checkbox" checked={auto} onChange={e => setAuto(e.target.checked)} />5초마다 갱신</label><span>최근 조회 {updated ? updated.toLocaleTimeString('ko-KR') : '—'}</span></div>
        {error && <p role="alert">{error}</p>}
        <div className="admin-seat-legend">{Object.entries(STATUS).map(([state, name]) => <span key={state}><i className={`admin-seat ${state}`} />{name} {seats.filter(s => (s.status || 'MISSING') === state).length}</span>)}</div>
        <div className="admin-seat-scroll"><div className="admin-screen">SCREEN</div>{Object.entries(rows).map(([row, items]) => <div className="admin-seat-row" key={row}><b>{row}</b>{items.map((seat, index) => <button key={seat.id} className={`admin-seat ${seat.status || 'MISSING'}${index > 0 && seat.adjacency_segment !== items[index - 1].adjacency_segment ? ' admin-aisle' : ''}`} aria-label={`${seat.seat_row}${seat.seat_number} ${STATUS[seat.status || 'MISSING']}`} aria-pressed={picked.includes(seat.id)} disabled={busy} onClick={() => { setPicked(old => old.includes(seat.id) ? old.filter(id => id !== seat.id) : [...old, seat.id]); setPreview(null); }}>{seat.seat_number}</button>)}</div>)}</div>
        {updated && seats.length === 0 && <p>좌석 배치가 없습니다. 시간표·좌석 보충을 실행해주세요.</p>}
        <div className="admin-seat-operations">
            <p>선택 {picked.length}석 · 좌석을 누르면 선택/해제됩니다. 함께 예매한 좌석은 예약 단위로 모두 처리합니다.</p>
            <div className="admin-actions">
                <button disabled={busy || !updated} onClick={() => { setPicked(seats.map(s => s.id)); setPreview(null); }}>모든 좌석 선택</button>
                <button disabled={busy || !picked.length} onClick={() => { setPicked([]); setPreview(null); }}>선택 해제</button>
                <button disabled={busy || !picked.length} onClick={() => prepare('cancel', 'selected')}>선택 좌석 예매 취소</button>
                <button disabled={busy || !updated} onClick={() => prepare('cancel', 'all')}>이 회차 전체 예매 취소</button>
                <button className="admin-danger" disabled={busy || !picked.length} onClick={() => prepare('purge', 'selected')}>선택 좌석 예매 기록 삭제</button>
                <button className="admin-danger" disabled={busy || !updated} onClick={() => prepare('purge', 'all')}>이 회차 전체 예매 기록 삭제</button>
            </div>
            <p className="admin-hint">취소는 좌석을 해제하고 이력을 남깁니다. 기록 삭제는 취소·만료된 과거 예매도 포함합니다. 전체 작업은 이 회차의 대기열도 처리합니다.</p>
        </div>
        {notice && <p className="admin-feedback" role="status">{notice}</p>}
        {preview && <div className="admin-card admin-delete-preview" role="region" aria-label="예매 처리 확인">
            <h3>{preview.scope.action === 'purge' ? '예매 기록 영구 삭제' : '예매 강제 취소'}</h3>
            <p>예약 {preview.reservations}건 · 대기 {preview.waitingQueues}건 · 대상 좌석: {preview.seats.join(', ') || '없음'}</p>
            <div className="admin-impact">{Object.entries(preview.counts).filter(([, n]) => n > 0).map(([name, count]) => <div key={name}><span>{NAMES[name] || name}</span><strong>{count}개</strong></div>)}</div>
            <p>{preview.scope.action === 'purge' ? '예매·결제·티켓 및 연결 기록을 영구 삭제합니다. 관련 회원의 요청 재시도 기록도 초기화됩니다. 영화·영화관·상영회차·좌석 배치·회원 계정은 유지됩니다.' : '상영 시각과 관계없이 예매·모의결제·티켓을 취소하고 좌석을 해제합니다. 이력은 남습니다.'}</p>
            <label>예매 처리 확인 문구 ‘{preview.scope.action === 'purge' ? '삭제' : '취소'}’ 입력<input disabled={busy} value={confirmation} onChange={e => setConfirmation(e.target.value)} /></label>
            <div className="admin-actions"><button className="admin-danger-solid" disabled={busy || !(preview.reservations + preview.waitingQueues) || confirmation !== (preview.scope.action === 'purge' ? '삭제' : '취소')} onClick={execute}>확인 후 실행</button><button disabled={busy} onClick={() => setPreview(null)}>돌아가기</button></div>
        </div>}
        {selectedSeat && <p className="admin-seat-detail"><strong>{selectedSeat.seat_row}{selectedSeat.seat_number} · {STATUS[selectedSeat.status || 'MISSING']}</strong><span>예약 ID: {selectedSeat.reservation_id ?? '—'}</span><span>선점 만료: {dateTime(selectedSeat.hold_expired_at)}</span></p>}
    </section>;
}
