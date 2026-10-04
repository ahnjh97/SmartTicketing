import { useEffect, useId, useRef, useState } from 'react';
import { adminApi } from '../api/admin.js';
import { seoulDate } from '../booking/state.js';
import './AdminDataPage.css';

const NAMES = {
    movies: '영화', theaters: '영화관', screens: '상영관', showtimes: '상영회차', seats: '좌석 배치', showtime_seats: '회차별 좌석', estimated_showtime_seats: '회차별 좌석 (예상)',
    reservations: '예약', reservation_seats: '예약 좌석', payments: '결제', tickets: '티켓', waiting_queues: '대기열',
    queue_counters: '대기 순번', notifications: '관련 알림', booking_operations: '관련 회원의 요청 재시도 기록',
    booking_group_holds: '그룹 선점', booking_request_groups: '예매 요청', booking_group_seat_preferences: '요청 좌석 선호',
    booking_group_theater_preferences: '요청 영화관 선호', user_preferred_theaters: '회원 선호 영화관', user_nearby_theaters: '회원 주변 영화관',
    detached_booking_groups: '선택 회차 연결 해제',
    theater_collection_progress: '자동 재수집을 위한 수집 완료 기록',
};
const STATUS = { AVAILABLE: '선택 가능', HOLDING: '선점 중', RESERVED: '예약 완료', BLOCKED: '차단', MISSING: '재고 미생성' };
const BRANDS = { CGV: 'CGV', LOTTE_CINEMA: '롯데시네마', MEGABOX: '메가박스' };
const SHOW_STATUS = { SCHEDULED: '상영 예정', CLOSED: '접수 마감', CANCELLED: '취소', COMPLETED: '상영 완료' };
function collectionResult(result) {
    const labels = { savedCount: '추가', insertedCount: '추가', updatedCount: '수정', skippedCount: '생략', skippedQueryCount: '생략한 수집', apiCallCount: '외부 조회', showtimes: '생성 회차', screens: '생성 상영관', showtime_seats: '생성 좌석 재고' };
    const parts = Object.entries(labels).filter(([key]) => result?.[key] !== undefined).map(([key, name]) => `${name} ${Number(result[key]).toLocaleString()}건`);
    if (result?.failedIds?.length) parts.push(`수집 실패 ${result.failedIds.length}건 (영화 ID: ${result.failedIds.join(', ')})`);
    return parts.join(' · ');
}
const dateTime = value => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '—';
const enabled = value => value === true || value === 1;

function DataTabIcon({ kind }) {
    return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
        {kind === 'showtimes' ? <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M7 3v4m10-4v4M3 10h18m-9 3v3l2 1" /></>
            : kind === 'movies' ? <><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M7 3v18m10-18v18M3 8h4m-4 8h4M17 8h4m-4 8h4" /></>
                : <><path d="M3 21h18M5 21V5l7-2 7 2v16M9 21v-5h6v5M8 8h1m6 0h1M8 12h1m6 0h1" /></>}
    </svg>;
}

export default function AdminDataPage() {
    return <div className="admin-data">
        <header className="admin-data-heading"><h1>데이터 관리</h1></header>
        <DataConsole />
    </div>;
}

function CollectionStatus({ revision, busy }) {
    const [state, setState] = useState({ items: null, key: null, error: '' });
    const [refresh, setRefresh] = useState(0);
    const key = `${revision}:${refresh}`;
    const loading = state.key !== key;
    useEffect(() => {
        if (busy) return;
        const controller = new AbortController();
        adminApi.collectionStatus(controller.signal)
            .then(items => { if (!controller.signal.aborted) setState({ items, key, error: '' }); })
            .catch(error => { if (!controller.signal.aborted) setState({ items: null, key, error: error.message }); });
        return () => controller.abort();
    }, [key, busy]);
    return <section className="admin-collection-status" aria-label="수집 현황" aria-busy={loading && !busy}>
        <div className="admin-status-heading"><h2>수집 현황</h2><span>{busy ? '작업 종료 후 갱신' : loading ? '집계 중…' : '건수 기준'}</span>
            <CollectionButton disabled={busy || loading} onClick={() => setRefresh(n => n + 1)} disabledReason={busy ? '관리자 작업이 진행 중입니다.' : '수집 현황을 집계 중입니다.'}>현황 새로고침</CollectionButton></div>
        {!loading && state.error && <p role="alert">현황을 불러오지 못했습니다. {state.error}</p>}
        {state.items && <div className="admin-status-counts">{state.items.map(item => <div key={item.key} className="admin-status-count" title={item.detail} tabIndex={0} aria-label={`${item.label}: ${item.count.toLocaleString()} / ${item.expected.toLocaleString()}, ${item.detail}`}>
            <span>{item.label}</span><strong>{item.count.toLocaleString()} <small>/ {item.expected.toLocaleString()}</small></strong>
            <span className={item.missing > 0 ? 'admin-status-missing' : ''}>{item.expected === 0 ? '대상 없음' : item.missing > 0 ? `미충족 ${item.missing.toLocaleString()}` : '충족'}</span>
        </div>)}</div>}
    </section>;
}

function DataConsole() {
    const [kind, setKind] = useState('showtimes');
    const [browse, setBrowse] = useState({ theaters: [], movies: [], dates: [] });
    const [applied, setApplied] = useState(() => ({ date: seoulDate() }));
    const [page, setPage] = useState(0);
    const [revision, setRevision] = useState(0);
    const [data, setData] = useState({ items: [], total: 0 });
    const [brand, setBrand] = useState('CGV');
    const [showMovieStatus, setShowMovieStatus] = useState('now');
    const [optionsLoading, setOptionsLoading] = useState(true);
    const [dateOptions, setDateOptions] = useState({ key: '', items: [] });
    const [dateRetry, setDateRetry] = useState(0);
    const [selected, setSelected] = useState([]);
    const [loading, setLoading] = useState(true);
    const [localBusy, setBusy] = useState(false);
    const task = useAdminTask();
    const busy = localBusy || task?.state === 'RUNNING';
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [preview, setPreview] = useState(null);
    const [includeBookings, setIncludeBookings] = useState(false);
    const [seatShow, setSeatShow] = useState(null);
    const [allBookingsOpen, setAllBookingsOpen] = useState(false);
    const browseRevision = useRef(-1);
    useEffect(() => {
        const refresh = () => { setSelected([]); setData({ items: [], total: 0 }); setLoading(true); setRevision(n => n + 1); };
        window.addEventListener('admin-task-finished', refresh);
        return () => window.removeEventListener('admin-task-finished', refresh);
    }, []);

    const ready = kind !== 'showtimes' || Boolean(applied.movieId && applied.theaterId && applied.date);
    const dateKey = `${applied.theaterId || ''}-${applied.movieId || ''}`;
    useEffect(() => {
        if (!ready) return;
        const controller = new AbortController();
        adminApi.list(kind, { ...applied, page }, controller.signal)
            .then(result => { if (!controller.signal.aborted) setData(result); })
            .catch(e => { if (!controller.signal.aborted) { setError(e.message); setData({ items: [], total: 0 }); } })
            .finally(() => { if (!controller.signal.aborted) setLoading(false); });
        return () => controller.abort();
    }, [kind, applied, page, revision, ready]);

    function startLoad() { setLoading(true); setError(''); setSelected([]); setPreview(null); setSeatShow(null); setData({ items: [], total: 0 }); }
    function reload() { startLoad(); setDateOptions({ key: '', items: [] }); setRevision(x => x + 1); }

    async function run(action) {
        setBusy(true); setError(''); setNotice('');
        try { await action(); } catch (e) { setError(e.message); } finally { setBusy(false); }
    }
    function changeKind(next) {
        startLoad();
        setKind(next); setPage(0); setApplied(next === 'movies' ? { movieStatus: 'now' } : next === 'showtimes' ? { date: seoulDate() } : {}); setPreview(null);
    }
    function choose(next) { startLoad(); setApplied(kind === 'showtimes' ? { date: seoulDate(), ...next } : next); setPage(0); }
    useEffect(() => {
        if (kind !== 'showtimes') return;
        if (browseRevision.current === revision) return;
        const controller = new AbortController();
        adminApi.browse({}, controller.signal)
            .then(result => { if (!controller.signal.aborted) { setBrowse(result); browseRevision.current = revision; } })
            .catch(e => { if (!controller.signal.aborted) setError(e.message); })
            .finally(() => { if (!controller.signal.aborted) setOptionsLoading(false); });
        return () => controller.abort();
    }, [kind, revision]);
    useEffect(() => {
        if (kind !== 'showtimes' || !applied.theaterId || !applied.movieId) return;
        const controller = new AbortController();
        adminApi.dates({ theaterId: applied.theaterId, movieId: applied.movieId }, controller.signal)
            .then(items => {
                if (!controller.signal.aborted) {
                    const today = seoulDate();
                    setDateOptions({ key: dateKey, items: [{ date: today }, ...items.filter(item => item.date !== today)].sort((a, b) => a.date.localeCompare(b.date)) });
                }
            })
            .catch(e => { if (!controller.signal.aborted) setDateOptions({ key: dateKey, items: [], error: e.message }); });
        return () => controller.abort();
    }, [kind, applied.theaterId, applied.movieId, dateKey, revision, dateRetry]);
    function prepareDelete(mode) {
        const scope = { kind, mode, ...(mode === 'selected' ? { ids: selected } : mode === 'filtered' ? applied : {}) };
        run(async () => {
            const result = await adminApi.preview(scope);
            setPreview({ ...result, scope }); setIncludeBookings(false);
        });
    }
    function deleteData() {
        run(async () => {
            const result = await adminApi.delete({ scope: preview.scope, fingerprint: preview.fingerprint, confirmation: '삭제', includeBookings });
            setNotice(`${NAMES[kind]} ${result.targetCount.toLocaleString()}개와 연결 데이터 삭제 완료`);
            setPage(0); reload();
        });
    }
    function collect(label, action) {
        run(async () => { const result = await action(); setNotice(`${label} 처리 결과 · ${collectionResult(result)}`); reload(); });
    }
    const disabled = busy || (ready && loading);
    const allChecked = data.items.length > 0 && data.items.every(item => selected.includes(item.id));

    return <>
        <section className="admin-card admin-collection">
            <h2>수집·생성</h2>
            <div className="admin-actions">
                <CollectionButton description="TMDB에서 삭제된 영화와 누락 정보를 수집합니다. 수집이 완료된 영화는 건너뜁니다." disabled={busy} onClick={() => collect('영화 수집', () => adminApi.collectMovies())}>영화 수집</CollectionButton>
                <CollectionButton description="서울의 주요 영화관을 수집합니다. 삭제한 영화관의 브랜드는 자동으로 다시 조회합니다." disabled={busy} onClick={() => collect('영화관 수집', () => adminApi.collectTheaters())}>영화관 수집</CollectionButton>
                <CollectionButton description="테스트용 상영회차와 누락된 좌석 데이터를 생성합니다. 실제 영화관 시간표를 수집하지는 않습니다." disabled={busy} onClick={() => collect('시간표·좌석 보충', () => adminApi.prepare())}>시간표·좌석 보충</CollectionButton>
                <CollectionButton className="admin-danger" disabled={busy} onClick={() => setAllBookingsOpen(true)}>모든 예매내역 삭제</CollectionButton>
            </div>
        </section>
        <CollectionStatus revision={revision} busy={busy} />
        {notice && <p className="admin-feedback" role="status">{notice}</p>}
        {task && ['RUNNING', 'FAILED'].includes(task.state) && <div className={`admin-feedback${task.state === 'FAILED' ? ' admin-error' : ''}`} role="status">
            <strong>{task.label} · {task.state === 'RUNNING' ? '진행 중' : '중단'}</strong>
            <span> {task.total > 0 ? `${task.completed} / ${task.total}개 · ` : ''}{task.detail}</span>
            {task.state === 'RUNNING' && <progress max={task.total || undefined} value={task.total ? task.completed : undefined} />}
        </div>}
        {task?.connectionError && <p role="alert">서버의 작업 상태를 확인할 수 없습니다. 연결이 복구되면 자동으로 다시 확인합니다.</p>}
        {busy && <p role="status">작업 중입니다. 수집·대량 삭제는 시간이 걸릴 수 있습니다.</p>}
        <section className="admin-card">
            <div className="admin-tabs" role="tablist" aria-label="데이터 종류">{['showtimes', 'movies', 'theaters'].map(name => <button key={name} role="tab" aria-selected={kind === name} disabled={busy} onClick={() => changeKind(name)}><DataTabIcon kind={name} /><span>{NAMES[name]}</span></button>)}</div>
            {kind === 'showtimes' && <div className="admin-browser">
                <div><div className="admin-list-heading admin-movie-selector-heading"><h3>1. 영화</h3><div className="admin-tabs" role="tablist" aria-label="상영회차 영화 분류">
                    {Object.entries({ now: '상영작', upcoming: '상영 예정작' }).map(([key, label]) => <button key={key} role="tab" aria-selected={showMovieStatus === key} disabled={busy} onClick={() => { setShowMovieStatus(key); choose(applied.theaterId ? { theaterId: applied.theaterId } : {}); }}>{label}</button>)}
                </div></div><div className="admin-choice-list" role="group" aria-label="영화 선택">
                    {browse.movies.filter(m => m.movie_status === showMovieStatus).map(m => <button key={m.id} aria-pressed={applied.movieId === m.id} disabled={busy || optionsLoading} onClick={() => choose({ movieId: m.id, ...(applied.theaterId ? { theaterId: applied.theaterId } : {}) })}>{m.title}</button>)}
                    {!optionsLoading && !browse.movies.some(m => m.movie_status === showMovieStatus) && <p>해당 분류의 영화가 없습니다.</p>}
                </div></div>
                <div><h3>2. 영화관</h3><div className="admin-theater-selector"><div className="admin-brand-tabs" role="tablist" aria-label="영화관 브랜드">
                    {Object.entries(BRANDS).map(([key, name]) => <button key={key} data-brand={key} role="tab" aria-selected={brand === key} disabled={busy} onClick={() => { setBrand(key); choose(applied.movieId ? { movieId: applied.movieId } : {}); }}>{name}</button>)}
                </div><div className="admin-branch-panel"><p className="admin-branch-label">{BRANDS[brand]} 지점 선택</p><div className="admin-choice-list" role="group" aria-label="영화관 선택">
                    {browse.theaters.filter(t => t.brand === brand).map(t => <button key={t.id} aria-pressed={applied.theaterId === t.id} disabled={busy || optionsLoading} onClick={() => choose({ theaterId: t.id, ...(applied.movieId ? { movieId: applied.movieId } : {}) })}>{t.name}</button>)}
                    {!optionsLoading && !browse.theaters.some(t => t.brand === brand) && <p>등록된 영화관이 없습니다.</p>}
                </div></div></div></div>
                <div><h3>3. 날짜</h3><div className="admin-choice-list" role="group" aria-label="날짜 선택">
                    {!applied.movieId || !applied.theaterId ? <p>영화와 영화관을 선택해주세요.</p> : dateOptions.key !== dateKey ? null : dateOptions.error ? <><span role="alert">날짜 조회 실패: {dateOptions.error}</span><button onClick={() => { setDateOptions({ key: '', items: [] }); setDateRetry(n => n + 1); }}>날짜 다시 불러오기</button></> : dateOptions.items.length === 0 ? <p>등록된 상영회차가 없습니다.</p> : dateOptions.items.map(d => <button key={d.date} aria-pressed={applied.date === d.date} disabled={busy} onClick={() => choose({ ...applied, date: d.date })}>{d.date}</button>)}
                </div></div>
            </div>}
            <div className="admin-actions admin-refresh"><CollectionButton disabled={disabled} onClick={reload} disabledReason={busy ? '관리자 작업이 진행 중입니다.' : '목록을 불러오는 중입니다.'}>새로고침</CollectionButton></div>
            <div className="admin-table-toolbar"><div className="admin-list-heading">
            {kind === 'movies' && <div className="admin-tabs admin-movie-tabs" role="tablist" aria-label="영화 분류">
                {Object.entries({ now: '상영작', upcoming: '상영 예정작' }).map(([key, label]) => <button key={key} role="tab" aria-selected={applied.movieStatus === key} disabled={busy} onClick={() => choose({ movieStatus: key })}>{label}</button>)}
            </div>}
            <span>목록 <b>{data.total.toLocaleString()}</b>개 · 선택 {selected.length}개</span></div><div className="admin-actions">
                <CollectionButton className="admin-danger" disabled={disabled || !selected.length} onClick={() => prepareDelete('selected')} disabledReason={busy ? '관리자 작업이 진행 중입니다.' : loading && ready ? '목록을 불러오는 중입니다.' : '삭제할 항목을 선택해주세요.'}>선택 삭제</CollectionButton>
                <CollectionButton className="admin-danger" disabled={disabled || !data.total} onClick={() => prepareDelete('filtered')} disabledReason={busy ? '관리자 작업이 진행 중입니다.' : loading && ready ? '목록을 불러오는 중입니다.' : !ready ? '영화·영화관·날짜를 선택해주세요.' : '삭제할 데이터가 없습니다.'}>{kind === 'movies' ? '현재 탭 전체 삭제' : '목록 전체 삭제'}</CollectionButton>
                <CollectionButton className="admin-danger" disabled={busy} onClick={() => prepareDelete('all')}>{NAMES[kind]} 전부 삭제</CollectionButton>
            </div></div>
            {busy && !preview && <p role="status">요청을 처리하고 있습니다. 삭제 요청은 영향 확인 후 실행할 수 있습니다.</p>}
            {error && !preview && <p className="admin-feedback admin-error" role="alert">{error}</p>}
            <p className="admin-hint">{kind === 'movies' ? '현재 탭 전체 삭제: 현재 분류의 모든 페이지 · 영화 전부 삭제: 상영작·예정작 모두 (개봉일 미정은 예정작에 포함)' : '‘전부 삭제’는 선택한 영화관·영화·날짜와 페이지에 관계없이 해당 종류 전체가 대상입니다. 삭제 전 연결 데이터 건수를 확인할 수 있습니다.'}</p>
            {kind === 'movies' ? <>
                <label className="admin-check"><input type="checkbox" aria-label="현재 페이지 전체 선택" checked={allChecked} disabled={disabled} onChange={e => { setSelected(e.target.checked ? data.items.map(item => item.id) : []); setPreview(null); }} />현재 페이지 전체 선택</label>
                {loading ? null : data.items.length === 0 ? <p>표시할 데이터가 없습니다.</p> : <div className="admin-movie-grid">{data.items.map(item => <label className={`admin-movie-card${selected.includes(item.id) ? ' is-selected' : ''}`} key={item.id}>
                    <input type="checkbox" aria-label={`${item.id} 선택`} checked={selected.includes(item.id)} disabled={disabled} onChange={e => { setSelected(old => e.target.checked ? [...old, item.id] : old.filter(id => id !== item.id)); setPreview(null); }} />
                    <MoviePoster item={item} />
                    <strong>{item.title}</strong><small>{item.running_time ?? '—'}분 · {item.rating || '등급 미설정'}</small>
                    <small>{item.release_date ? `${seoulDate(new Date(item.release_date))} 개봉` : '개봉일 미정'}</small>
                    <small>#{item.id} · {enabled(item.is_active) ? '서비스 노출' : '서비스 비노출'}</small>
                </label>)}</div>}
            </> : <div className="admin-table-scroll"><table><thead><tr><th><input type="checkbox" aria-label="현재 페이지 전체 선택" checked={allChecked} disabled={disabled} onChange={e => { setSelected(e.target.checked ? data.items.map(item => item.id) : []); setPreview(null); }} /></th><th>ID</th><th>{kind === 'showtimes' ? '영화 / 영화관' : '이름'}</th><th>정보</th><th>상태</th>{kind === 'showtimes' && <th>관리</th>}</tr></thead>
                <tbody>{!ready ? <tr><td colSpan="6">영화·영화관·날짜를 선택하면 상영회차가 표시됩니다.</td></tr> : loading ? null : data.items.length === 0 ? <tr><td colSpan="6">표시할 데이터가 없습니다.</td></tr> : data.items.map(item => <tr key={item.id}>
                    <td><input type="checkbox" aria-label={`${item.id} 선택`} checked={selected.includes(item.id)} disabled={disabled} onChange={e => { setSelected(old => e.target.checked ? [...old, item.id] : old.filter(id => id !== item.id)); setPreview(null); }} /></td>
                    <td>{item.id}</td><td><strong>{item.title || item.name}</strong>{kind === 'showtimes' && <small>{item.theater_name} · {item.screen_name}</small>}</td>
                    <td>{kind === 'showtimes' ? <>{dateTime(item.start_time)}<small>{item.available_seats} / {item.total_seats}석 · {item.price_per_person?.toLocaleString() ?? '미설정'}원</small></> : item.address}</td>
                    <td>{kind === 'showtimes' ? (SHOW_STATUS[item.status] || item.status) : enabled(item.is_active) ? '서비스 노출' : '서비스 비노출'}</td>{kind === 'showtimes' && <td><div className="admin-actions">
                        <CollectionButton disabled={disabled} onClick={() => setSeatShow(item)} disabledReason={busy ? '관리자 작업이 진행 중입니다.' : '목록을 불러오는 중입니다.'}>좌석 현황</CollectionButton>
                    </div></td>}
                </tr>)}</tbody></table></div>}
            <div className="admin-pagination"><button disabled={disabled || page === 0} onClick={() => { startLoad(); setPage(x => x - 1); }}>이전</button><span>{page + 1} / {Math.max(1, Math.ceil(data.total / 50))} 페이지</span><button disabled={disabled || (page + 1) * 50 >= data.total} onClick={() => { startLoad(); setPage(x => x + 1); }}>다음</button></div>
        </section>
        {preview && <AdminModal title="삭제 영향 확인" busy={busy} onClose={() => setPreview(null)}><section className="admin-delete-preview" aria-label="삭제 영향 확인">
            <h2>삭제 영향 확인</h2><p><strong>{NAMES[preview.scope.kind]} {preview.targetCount.toLocaleString()}개</strong> · {({ selected: '선택한 항목', filtered: '목록 전체', all: '모든 항목 (목록 선택 무시)' })[preview.scope.mode]}</p>
            {error && <><p className="admin-feedback admin-error" role="alert">{error}</p><CollectionButton disabled={busy} onClick={() => run(async () => {
                const result = await adminApi.preview(preview.scope);
                setPreview({ ...result, scope: preview.scope }); setIncludeBookings(false);
            })}>삭제 대상 다시 확인</CollectionButton></>}
            <div className="admin-impact">{Object.entries(preview.counts).filter(([, n]) => n > 0).map(([name, count]) => <div key={name}><span>{NAMES[name] || name}</span><strong>{count.toLocaleString()}개</strong></div>)}</div>
            <p>삭제는 되돌릴 수 없습니다. 연결된 데이터도 함께 정리되며, 회원 계정은 유지됩니다. 관련 회원의 요청 재시도 기록은 함께 초기화됩니다.</p>
            <p>대량 삭제는 나누어 처리합니다. 중단되면 완료한 부분은 유지되며, 남은 데이터를 다시 선택해 처리할 수 있습니다.</p>
            {preview.hasBookings && <label className="admin-check"><input type="checkbox" checked={includeBookings} onChange={e => setIncludeBookings(e.target.checked)} disabled={busy} />연결된 예약·결제·티켓·대기 데이터 삭제에 동의합니다.</label>}
            <div className="admin-actions"><CollectionButton className="admin-danger-solid" disabled={busy || Boolean(error) || preview.targetCount === 0 || (preview.hasBookings && !includeBookings)} onClick={deleteData} disabledReason={busy ? '관리자 작업이 진행 중입니다.' : error ? '삭제 대상을 다시 확인해주세요.' : preview.targetCount === 0 ? '삭제할 데이터가 없습니다.' : '연결된 예매 데이터 삭제에 동의해주세요.'}>영구 삭제 실행</CollectionButton><button disabled={busy} onClick={() => setPreview(null)}>취소</button></div>
        </section></AdminModal>}
        {allBookingsOpen && <AllBookingsDialog close={() => setAllBookingsOpen(false)} onBusy={setBusy} onChanged={() => { reload(); setNotice('모든 예매내역을 삭제하고 좌석을 해제했습니다.'); }} />}
        {seatShow && <SeatPanel key={seatShow.id} show={seatShow} close={() => setSeatShow(null)} onBusy={setBusy} onChanged={() => setRevision(x => x + 1)} />}
    </>;
}

function AdminModal({ title, busy, onClose, children, wide = false }) {
    const dialog = useRef(null);
    const [task, setTask] = useState(null);
    useEffect(() => {
        const update = event => setTask(event.detail);
        window.addEventListener('admin-task', update);
        return () => window.removeEventListener('admin-task', update);
    }, []);
    useEffect(() => {
        const element = dialog.current;
        const previousOverflow = document.body.style.overflow;
        element.showModal();
        document.body.style.overflow = 'hidden';
        return () => { element.close(); document.body.style.overflow = previousOverflow; };
    }, []);
    return <dialog ref={dialog} className={`admin-modal${wide ? ' admin-modal-wide' : ''}`} aria-label={title} onCancel={e => { e.preventDefault(); if (!busy) onClose(); }}>
        <button className="admin-modal-close" aria-label={`${title} 닫기`} disabled={busy} onClick={onClose}>×</button>
        {task?.state === 'RUNNING' && <p role="status">{task.detail} {task.total > 0 && `(${task.completed}/${task.total})`}</p>}{children}
    </dialog>;
}

function useAdminTask() {
    const [task, setTask] = useState(null);
    useEffect(() => {
        const controller = new AbortController();
        let timer;
        let last;
        let eventVersion = 0;
        const accept = current => {
            if (last?.state === 'RUNNING' && (current?.state === 'IDLE' || current?.id !== last.id || ['COMPLETED', 'FAILED'].includes(current.state)))
                window.dispatchEvent(new Event('admin-task-finished'));
            last = current; setTask(current);
        };
        const update = event => { eventVersion++; accept(event.detail); };
        const poll = async () => {
            const version = eventVersion;
            try { const current = await adminApi.task(controller.signal); if (!controller.signal.aborted && version === eventVersion) accept(current); }
            catch { if (!controller.signal.aborted && version === eventVersion) setTask(previous => ({ ...previous, connectionError: true })); }
            finally { if (!controller.signal.aborted) timer = setTimeout(poll, 3000); }
        };
        window.addEventListener('admin-task', update);
        poll();
        return () => { controller.abort(); clearTimeout(timer); window.removeEventListener('admin-task', update); };
    }, []);
    return task;
}

function AllBookingsDialog({ close, onBusy, onChanged }) {
    const [preview, setPreview] = useState(null);
    const [error, setError] = useState('');
    const [busy, setBusy] = useState(false);
    const [retry, setRetry] = useState(0);
    const scope = { showtimeId: 0, mode: 'global', action: 'purge' };
    useEffect(() => {
        let active = true;
        adminApi.bookingPreview({ showtimeId: 0, mode: 'global', action: 'purge' }).then(result => { if (active) setPreview(result); }).catch(e => { if (active) setError(e.message); });
        return () => { active = false; };
    }, [retry]);
    async function execute() {
        setBusy(true); onBusy(true); setError('');
        try {
            await adminApi.bookingExecute({ scope, fingerprint: preview.fingerprint, confirmation: '삭제' });
            onChanged(); close();
        } catch (e) { setError(e.message); setPreview(null); }
        finally { setBusy(false); onBusy(false); }
    }
    return <AdminModal title="모든 예매내역 삭제" busy={busy} onClose={close}>
        <h2>모든 예매내역 삭제</h2>
        <p>모든 회원·회차의 예매, 결제, 티켓, 대기 및 예매 요청 기록을 삭제하고 좌석을 해제합니다. 영화·영화관·상영회차·회원 계정은 유지됩니다.</p>
        {error && <><p role="alert">{error}</p><button onClick={() => { setError(''); setPreview(null); setRetry(n => n + 1); }}>삭제 대상 다시 확인</button></>}
        {!preview && !error && <p role="status">삭제 대상을 확인하는 중…</p>}
        {preview && <>{!Object.values(preview.counts).some(n => n > 0) && <p>삭제할 예매 관련 기록이 없습니다.</p>}<div className="admin-impact">{Object.entries(preview.counts).filter(([, n]) => n > 0).map(([key, value]) => <div key={key}><span>{NAMES[key] || key}</span><strong>{value}개</strong></div>)}</div>
            <div className="admin-actions"><CollectionButton className="admin-danger-solid" disabled={busy || !Object.values(preview.counts).some(n => n > 0)} onClick={execute} disabledReason={busy ? '삭제 작업이 진행 중입니다.' : '삭제할 예매 관련 기록이 없습니다.'}>모든 예매내역 영구 삭제</CollectionButton><button disabled={busy} onClick={close}>취소</button></div></>}
    </AdminModal>;
}

function MoviePoster({ item }) {
    const [failed, setFailed] = useState(false);
    return <div className="admin-movie-poster">{item.poster_url && !failed ? <img src={item.poster_url} alt={`${item.title} 포스터`} loading="lazy" onError={() => setFailed(true)} /> : <span>포스터 없음</span>}</div>;
}

function CollectionButton({ description, disabledReason = '관리자 작업이 진행 중입니다.', children, disabled, ...props }) {
    const id = useId();
    const [open, setOpen] = useState(false);
    const message = disabled ? disabledReason : description;
    return <span className="admin-tooltip" tabIndex={disabled ? 0 : undefined}
        aria-label={disabled ? `${children}: ${message}` : undefined}
        onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}
        onFocus={() => setOpen(true)} onBlur={() => setOpen(false)}
        onKeyDown={e => { if (e.key === 'Escape') setOpen(false); }}>
        <button {...props} disabled={disabled} aria-describedby={message ? id : undefined}>{children}</button>
        {message && <span id={id} className="admin-tooltip-content" role="tooltip" hidden={!open}>{message}</span>}
    </span>;
}

function SeatPanel({ show, close, onBusy, onChanged }) {
    const [seats, setSeats] = useState([]);
    const [error, setError] = useState('');
    const [updated, setUpdated] = useState(null);
    const [settled, setSettled] = useState(0);
    const [loadedRevision, setLoadedRevision] = useState(-1);
    const [seatError, setSeatError] = useState('');
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
            await adminApi.bookingExecute({ scope: preview.scope, fingerprint: preview.fingerprint, confirmation: preview.scope.action === 'purge' ? '삭제' : confirmation });
            setNotice(preview.scope.action === 'purge' ? '예매 기록을 삭제했습니다.' : '예매를 취소했습니다.');
            setPreview(null); setPicked([]); setRevision(x => x + 1); onChanged();
        } catch (e) { setError(e.message); setPreview(null); setConfirmation(''); setRevision(x => x + 1); }
        finally { setBusy(false); onBusy(false); }
    }
    useEffect(() => {
        const controller = new AbortController();
        adminApi.seats(show.id, controller.signal).then(items => {
            if (!controller.signal.aborted) { setSeats(items); setUpdated(new Date()); setSeatError(''); }
        }).catch(e => { if (!controller.signal.aborted) setSeatError(e.message); })
            .finally(() => { if (!controller.signal.aborted) { setSettled(n => n + 1); setLoadedRevision(revision); } });
        return () => controller.abort();
    }, [show.id, revision]);
    useEffect(() => { if (!auto || busy || preview || !settled || loadedRevision !== revision) return; const timer = setTimeout(() => setRevision(x => x + 1), 5000); return () => clearTimeout(timer); }, [auto, busy, preview, settled, revision, loadedRevision]);
    const seatsLoading = loadedRevision !== revision;
    const seatsUnavailable = seatsLoading || Boolean(seatError);
    const seatReason = busy ? '관리자 작업이 진행 중입니다.' : seatsLoading ? '좌석 정보를 불러오는 중입니다.' : seatError ? '좌석 조회에 실패했습니다. 다시 조회해주세요.' : '선택한 좌석이 없습니다.';
    const rows = Object.groupBy(seats, seat => seat.seat_row);
    const selectedSeat = seats.find(s => s.id === picked.at(-1));
    return <AdminModal title="좌석 현황" busy={busy} onClose={close} wide><section className="admin-seat-panel"><div className="admin-table-toolbar"><div><h2>좌석 현황 · {show.title}</h2><p>{show.theater_name} · {show.screen_name} · {dateTime(show.start_time)}</p></div><button disabled={busy} onClick={close}>닫기</button></div>
        <div className="admin-actions"><CollectionButton disabled={busy || seatsLoading} disabledReason={seatReason} onClick={() => { setPreview(null); setRevision(x => x + 1); }}>좌석 새로고침</CollectionButton><label className="admin-check"><input type="checkbox" checked={auto} onChange={e => setAuto(e.target.checked)} />5초마다 갱신</label><span>최근 조회 {updated ? updated.toLocaleTimeString('ko-KR') : '—'}</span></div>
        {error && <p role="alert">{error}</p>}
        
        {seatError && !seatsLoading && <p role="alert">좌석 조회 실패: {seatError}{updated && ' · 표시된 좌석은 마지막 조회 정보입니다.'}</p>}
        <div className="admin-seat-legend">{Object.entries(STATUS).map(([state, name]) => <span key={state}><i className={`admin-seat ${state}`} />{name} {seats.filter(s => (s.status || 'MISSING') === state).length}</span>)}</div>
        <div className="admin-seat-scroll"><div className="admin-screen">SCREEN</div>{Object.entries(rows).map(([row, items]) => <div className="admin-seat-row" key={row}><b>{row}</b>{items.map((seat, index) => <button key={seat.id} className={`admin-seat ${seat.status || 'MISSING'}${index > 0 && seat.adjacency_segment !== items[index - 1].adjacency_segment ? ' admin-aisle' : ''}`} aria-label={`${seat.seat_row}${seat.seat_number} ${STATUS[seat.status || 'MISSING']}`} aria-pressed={picked.includes(seat.id)} disabled={busy || seatsUnavailable} onClick={() => { setPicked(old => old.includes(seat.id) ? old.filter(id => id !== seat.id) : [...old, seat.id]); setPreview(null); }}>{seat.seat_number}</button>)}</div>)}</div>
        {updated && seats.length === 0 && <p>좌석 배치가 없습니다. 시간표·좌석 보충을 실행해주세요.</p>}
        <div className="admin-seat-operations">
            <p>선택 {picked.length}석 · 좌석을 누르면 선택/해제됩니다. 함께 예매한 좌석은 예약 단위로 모두 처리합니다.</p>
            <div className="admin-actions">
                <CollectionButton disabled={busy || seatsUnavailable || !updated} onClick={() => { setPicked(seats.map(s => s.id)); setPreview(null); }} disabledReason={seatReason}>모든 좌석 선택</CollectionButton>
                <CollectionButton disabled={busy || seatsUnavailable || !picked.length} onClick={() => { setPicked([]); setPreview(null); }} disabledReason={busy || seatsUnavailable ? seatReason : '선택한 좌석이 없습니다.'}>선택 해제</CollectionButton>
                <CollectionButton disabled={busy || seatsUnavailable || !picked.length} onClick={() => prepare('cancel', 'selected')} disabledReason={busy || seatsUnavailable ? seatReason : '취소할 예매의 좌석을 선택해주세요.'}>선택 좌석 예매 취소</CollectionButton>
                <CollectionButton disabled={busy || seatsUnavailable || !updated} onClick={() => prepare('cancel', 'all')} disabledReason={seatReason}>이 회차 전체 예매 취소</CollectionButton>
                <CollectionButton className="admin-danger" disabled={busy || seatsUnavailable || !picked.length} onClick={() => prepare('purge', 'selected')} disabledReason={busy || seatsUnavailable ? seatReason : '기록을 삭제할 좌석을 선택해주세요.'}>선택 좌석 예매 기록 삭제</CollectionButton>
                <CollectionButton className="admin-danger" disabled={busy || seatsUnavailable || !updated} onClick={() => prepare('purge', 'all')} disabledReason={seatReason}>이 회차 전체 예매 기록 삭제</CollectionButton>
            </div>
            <p className="admin-hint">취소는 좌석을 해제하고 이력을 남깁니다. 기록 삭제는 취소·만료된 과거 예매도 포함합니다. 전체 작업은 이 회차의 대기열도 처리합니다.</p>
        </div>
        {notice && <p className="admin-feedback" role="status">{notice}</p>}
        {preview && <AdminModal title="예매 처리 확인" busy={busy} onClose={() => setPreview(null)}><div className="admin-delete-preview" role="region" aria-label="예매 처리 확인">
            <h3>{preview.scope.action === 'purge' ? '예매 기록 영구 삭제' : '예매 강제 취소'}</h3>
            <p>예약 {preview.reservations}건 · 대기 {preview.waitingQueues}건 · 대상 좌석: {preview.seats.join(', ') || '없음'}</p>
            <div className="admin-impact">{Object.entries(preview.counts).filter(([, n]) => n > 0).map(([name, count]) => <div key={name}><span>{NAMES[name] || name}</span><strong>{count}개</strong></div>)}</div>
            <p>{preview.scope.action === 'purge' ? '예매·결제·티켓 및 연결 기록을 영구 삭제합니다. 관련 회원의 요청 재시도 기록도 초기화됩니다. 영화·영화관·상영회차·좌석 배치·회원 계정은 유지됩니다.' : '상영 시각과 관계없이 예매·모의결제·티켓을 취소하고 좌석을 해제합니다. 이력은 남습니다.'}</p>
            {preview.scope.action === 'cancel' && preview.reservations + preview.waitingQueues > 0 && <label>예매 처리 확인 문구 ‘취소’ 입력<input disabled={busy} value={confirmation} onChange={e => setConfirmation(e.target.value)} /></label>}
            <div className="admin-actions"><CollectionButton className="admin-danger-solid" disabled={busy || !(preview.reservations + preview.waitingQueues) || (preview.scope.action === 'cancel' && confirmation !== '취소')} onClick={execute} disabledReason={busy ? '예매 처리 중입니다.' : !(preview.reservations + preview.waitingQueues) ? '처리할 예매·대기 내역이 없습니다.' : '확인 문구 ‘취소’를 입력해주세요.'}>확인 후 실행</CollectionButton><button disabled={busy} onClick={() => setPreview(null)}>돌아가기</button></div>
        </div></AdminModal>}
        {selectedSeat && <p className="admin-seat-detail"><strong>{selectedSeat.seat_row}{selectedSeat.seat_number} · {STATUS[selectedSeat.status || 'MISSING']}</strong><span>예약 ID: {selectedSeat.reservation_id ?? '—'}</span><span>선점 만료: {dateTime(selectedSeat.hold_expired_at)}</span></p>}
    </section></AdminModal>;
}
