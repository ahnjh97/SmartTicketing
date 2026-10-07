import { formatShowtime } from '../utils/showtimeFormat.js';
import InlineDetails from './InlineDetails.jsx';
import BrandLogo from './BrandLogo.jsx';
import HeaderTicketPreview from './HeaderTicketPreview.jsx';
import { useEffect, useRef, useState } from "react";
import "../styles/CommonHeader.css";
import { PAGE_PATHS } from "../navigation.js";
import { Link, matchPath, NavLink, useLocation, useNavigate } from "react-router-dom";
import { notificationApi } from "../api/notifications.js";
import { ticketApi } from "../api/tickets.js";

const TYPE_LABELS = {
    QUEUE_TURN: "대기 순서",
    SEAT_HOLD_EXPIRED: "좌석 선점 만료",
    PAYMENT_FAILED: "결제 실패",
};

function formatDate(value) {
    return value ? formatShowtime(value) : "-";
}

export default function CommonHeader(props) {
    return <HeaderContent key={`${props.user?.id ?? 'guest'}:${Boolean(props.disabled)}`} {...props} />;
}

function HeaderContent({ user, disabled, setupRequired }) {
    const { pathname } = useLocation();
    const navigate = useNavigate();
    const headerRef = useRef(null);
    const innerRef = useRef(null);
    const brandRef = useRef(null);
    const navRef = useRef(null);
    const accountRef = useRef(null);
    const toggleRef = useRef(null);
    const previewTimerRef = useRef(null);
    const [compact, setCompact] = useState(false);
    const [openLocation, setOpenLocation] = useState(null);
    const [openPanel, setOpenPanel] = useState(null);
    const [unreadCount, setUnreadCount] = useState(0);
    const [notifications, setNotifications] = useState([]);
    const [tickets, setTickets] = useState([]);
    const [previewTicket, setPreviewTicket] = useState(null);
    const [notificationLoading, setNotificationLoading] = useState(false);
    const [ticketLoading, setTicketLoading] = useState(false);

    const menuOpen = Boolean(user) && compact && openLocation === pathname;
    const activeMenu = pathname === PAGE_PATHS.home || matchPath(`${PAGE_PATHS.movies}/*`, pathname)
        ? "movies"
        : matchPath(`${PAGE_PATHS.theaters}/*`, pathname) ? "theaters" : null;
    const navigationDisabled = disabled || Boolean(user && setupRequired);
    const validTickets = tickets.filter((ticket) => ticket.status === "VALID");

    useEffect(() => () => {
        if (previewTimerRef.current) window.clearTimeout(previewTimerRef.current);
    }, []);

    function showTicketPreview(ticket) {
        if (!ticket || ticket.status !== "VALID") return;
        if (previewTimerRef.current) window.clearTimeout(previewTimerRef.current);
        setPreviewTicket(ticket);
    }

    function hideTicketPreview() {
        if (previewTimerRef.current) window.clearTimeout(previewTimerRef.current);
        previewTimerRef.current = window.setTimeout(() => setPreviewTicket(null), 450);
    }

    function keepTicketPreview() {
        if (previewTimerRef.current) window.clearTimeout(previewTimerRef.current);
    }

    function closeTicketPreview() {
        if (previewTimerRef.current) window.clearTimeout(previewTimerRef.current);
        setPreviewTicket(null);
    }

    function renderGuestItems() {
        return (
            <NavLink
                to={PAGE_PATHS.login}
                className="common-header-icon-trigger"
                aria-label="로그인"
                title="로그인"
                aria-disabled={navigationDisabled || undefined}
                tabIndex={navigationDisabled ? -1 : undefined}
                onClick={handleNavigation}
            >
                <span className="common-header-trigger-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none">
                        <circle cx="12" cy="8" r="3.2" />
                        <path d="M5.5 20c.8-3.3 3.1-5 6.5-5s5.7 1.7 6.5 5" />
                    </svg>
                </span>
            </NavLink>
        );
    }

    useEffect(() => {
        if (!user || disabled) return;

        let mounted = true;
        const loadUnread = () => notificationApi.list(true)
            .then((items) => {
                if (mounted) setUnreadCount(Array.isArray(items) ? items.length : 0);
            })
            .catch(() => {
                if (mounted) setUnreadCount(0);
            });

        loadUnread();
        const timer = window.setInterval(loadUnread, 30000);
        return () => {
            mounted = false;
            window.clearInterval(timer);
        };
    }, [user, disabled]);

    useEffect(() => {
        if (typeof ResizeObserver === "undefined") return;
        const observer = new ResizeObserver(() => {
            const inner = innerRef.current;
            const brand = brandRef.current;
            const nav = navRef.current;
            const account = accountRef.current;
            if (!inner || !brand || !nav || !account) return;

            const style = getComputedStyle(inner);
            const requiredWidth = brand.scrollWidth + nav.scrollWidth
                + account.scrollWidth + 40 + parseFloat(style.paddingLeft) + parseFloat(style.paddingRight);
            const needsCompact = inner.clientWidth < requiredWidth;
            setCompact(needsCompact);
            if (!needsCompact) {
                setOpenLocation(null);
                setOpenPanel(null);
            }
        });

        [innerRef, brandRef, navRef, accountRef]
            .map((ref) => ref.current)
            .filter(Boolean)
            .forEach((element) => observer.observe(element));
        return () => observer.disconnect();
    }, []);

    useEffect(() => {
        if (!openPanel && !menuOpen) return;

        function closeOnOutsideClick(event) {
            if (!headerRef.current?.contains(event.target)) {
                setOpenPanel(null);
                setOpenLocation(null);
            }
        }

        function closeOnEscape(event) {
            if (event.key === "Escape") {
                setOpenPanel(null);
                setOpenLocation(null);
                setPreviewTicket(null);
                toggleRef.current?.focus();
            }
        }

        document.addEventListener("pointerdown", closeOnOutsideClick);
        document.addEventListener("keydown", closeOnEscape);
        return () => {
            document.removeEventListener("pointerdown", closeOnOutsideClick);
            document.removeEventListener("keydown", closeOnEscape);
        };
    }, [openPanel, menuOpen]);

    function togglePanel(panel) {
        if (navigationDisabled) return;
        setOpenLocation(null);
        setOpenPanel((current) => current === panel ? null : panel);
        setPreviewTicket(null);

        if (panel === "notifications") {
            setNotificationLoading(true);
            notificationApi.list(false)
                .then((items) => {
                    const next = Array.isArray(items) ? items : [];
                    setNotifications(next);
                    setUnreadCount(next.filter((item) => !item.read).length);
                })
                .catch(() => setNotifications([]))
                .finally(() => setNotificationLoading(false));
        }

        if (panel === "tickets") {
            setTicketLoading(true);
            ticketApi.mine()
                .then((items) => setTickets(Array.isArray(items) ? items : []))
                .catch(() => setTickets([]))
                .finally(() => setTicketLoading(false));
        }
    }

    async function markNotificationRead(id) {
        try {
            await notificationApi.read(id);
            setNotifications((items) => {
                const target = items.find((item) => item.id === id);
                if (target && !target.read) setUnreadCount((count) => Math.max(0, count - 1));
                return items.map((item) => item.id === id ? { ...item, read: true } : item);
            });
        } catch {
            // 목록 UI는 유지한다.
        }
    }

    async function markAllNotificationsRead() {
        try {
            await notificationApi.readAll();
            setNotifications((items) => items.map((item) => ({ ...item, read: true })));
            setUnreadCount(0);
        } catch {
            // 다음 갱신에서 다시 확인한다.
        }
    }

    async function removeNotification(id) {
        try {
            await notificationApi.delete(id);
            setNotifications((items) => {
                const target = items.find((item) => item.id === id);
                if (target && !target.read) setUnreadCount((count) => Math.max(0, count - 1));
                return items.filter((item) => item.id !== id);
            });
        } catch {
            // 삭제 실패 시 현재 목록을 유지한다.
        }
    }

    function openNotification(item) {
        if (!item.groupId) return;
        setOpenPanel(null);
        setOpenLocation(null);
        if (item.type === "SEAT_HOLD_EXPIRED") {
            navigate("/bookings?history=1");
            return;
        }
        if (item.type === "QUEUE_TURN") {
            navigate(`/booking/restore?group=${encodeURIComponent(item.groupId)}`);
        }
    }

    function handleNavigation(event) {
        if (navigationDisabled) {
            event.preventDefault();
            return;
        }
        setOpenLocation(null);
        setOpenPanel(null);
        setPreviewTicket(null);
    }

    function renderAccountItems() {
        return (
            <>
                {user?.admin === true && (
                    <button type="button" className="common-header-icon-trigger" aria-label="데이터 관리" title="데이터 관리" disabled={disabled} onClick={() => { if (!disabled) { setOpenPanel(null); setOpenLocation(null); navigate(PAGE_PATHS.adminData); } }}>
                        <span className="common-header-trigger-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="m9.5 3-.5 2a8 8 0 0 0-1.5.9l-2-.6L3 9.5l1.5 1.4a8 8 0 0 0 0 2.2L3 14.5l2.5 4.2 2-.6A8 8 0 0 0 9 19l.5 2h5l.5-2a8 8 0 0 0 1.5-.9l2 .6 2.5-4.2-1.5-1.4a8 8 0 0 0 0-2.2L21 9.5l-2.5-4.2A8 8 0 0 0 15 5l-.5-2Z" /><circle cx="12" cy="12" r="3" /></svg></span>
                    </button>
                )}

                <button type="button" className="common-header-icon-trigger" aria-label={openPanel === "tickets" ? "내 티켓 닫기" : "내 티켓 열기"} aria-expanded={openPanel === "tickets"} onClick={() => togglePanel("tickets")} disabled={navigationDisabled}>
                    <span className="common-header-trigger-icon" aria-hidden="true"><svg className="common-header-ticket-icon" viewBox="0 0 24 24" fill="none"><path d="M4 5h16a2 2 0 0 1 2 2v2a3 3 0 0 0 0 6v2a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-2a3 3 0 0 0 0-6V7a2 2 0 0 1 2-2Z" /><path d="M13 5v2m0 4v2m0 4v2" /></svg></span>
                </button>

                <button type="button" className="common-header-notification-trigger" aria-label={openPanel === "notifications" ? "알림 닫기" : "알림 열기"} aria-expanded={openPanel === "notifications"} onClick={() => togglePanel("notifications")} disabled={navigationDisabled}>
                    <span className="common-header-trigger-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9Z" /><path d="M10 21h4" /></svg></span>
                    {unreadCount > 0 && <span className="common-header-notification-badge">{unreadCount > 99 ? "99+" : unreadCount}</span>}
                </button>

                <NavLink to={PAGE_PATHS.activeBookings} className="common-header-icon-trigger" aria-label="내 대기 및 선점" title="내 대기 및 선점" aria-disabled={navigationDisabled || undefined} tabIndex={navigationDisabled ? -1 : undefined} onClick={handleNavigation}>
                    <span className="common-header-trigger-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M5 11V6a3 3 0 0 1 3-3h5a3 3 0 0 1 3 3v5h1" /><path d="M5 11H4a2 2 0 0 0-2 2v3a2 2 0 0 0 2 2h7M5 11v3h6M5 18v3" /><circle cx="17.5" cy="17.5" r="4.5" /><path d="M17.5 15v2.5l1.5 1" /></svg></span>
                </NavLink>

                <NavLink to={PAGE_PATHS.profile} className="common-header-icon-trigger" aria-label="마이페이지" aria-disabled={navigationDisabled || undefined} tabIndex={navigationDisabled ? -1 : undefined} onClick={handleNavigation}>
                    <span className="common-header-trigger-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><circle cx="12" cy="8" r="3.2" /><path d="M5.5 20c.8-3.3 3.1-5 6.5-5s5.7 1.7 6.5 5" /></svg></span>
                </NavLink>
            </>
        );
    }

    return (
        <header ref={headerRef} className={`common-header${compact ? " common-header-compact" : ""}`}>
            <div ref={innerRef} className="common-header-inner">
                <NavLink to={PAGE_PATHS.home} end className="common-header-brand" ref={brandRef} aria-label="SmartTicketing" aria-disabled={navigationDisabled || undefined} tabIndex={navigationDisabled ? -1 : undefined} onClick={handleNavigation}><BrandLogo decorative /></NavLink>
                <div className="common-header-desktop" aria-hidden={compact || undefined} inert={compact || undefined}>
                    <nav ref={navRef} className="common-header-nav" aria-label="주 메뉴">
                        {[{ view: "movies", label: "영화" }, { view: "theaters", label: "극장" }].map(({ view, label }) => <Link key={view} to={view === "movies" ? PAGE_PATHS.home : PAGE_PATHS[view]} className={`common-header-link common-header-menu-link${activeMenu === view ? " is-active" : ""}`} aria-current={activeMenu === view ? "page" : undefined} aria-disabled={navigationDisabled || undefined} tabIndex={navigationDisabled ? -1 : undefined} onClick={handleNavigation}>{label}</Link>)}
                    </nav>
                    <nav ref={accountRef} className="common-header-account" aria-label="회원 메뉴">{user ? renderAccountItems() : renderGuestItems()}</nav>
                </div>
                {compact && <><nav className="common-header-nav common-header-compact-nav" aria-label="주 메뉴">{[{ view: "movies", label: "영화" }, { view: "theaters", label: "극장" }].map(({ view, label }) => <Link key={view} to={view === "movies" ? PAGE_PATHS.home : PAGE_PATHS[view]} className={`common-header-link common-header-menu-link${activeMenu === view ? " is-active" : ""}`} aria-current={activeMenu === view ? "page" : undefined} aria-disabled={navigationDisabled || undefined} tabIndex={navigationDisabled ? -1 : undefined} onClick={handleNavigation}>{label}</Link>)}</nav>{user ? <button ref={toggleRef} type="button" className="common-header-menu-toggle" aria-label={menuOpen ? "메뉴 닫기" : "메뉴 열기"} aria-expanded={menuOpen} aria-controls="common-header-mobile-menu" onClick={() => { setOpenPanel(null); setPreviewTicket(null); setOpenLocation(menuOpen ? null : pathname); }}><svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">{menuOpen ? <path d="m6 6 12 12M18 6 6 18" /> : <path d="M4 6h16M4 12h16M4 18h16" />}</svg></button> : <nav className="common-header-account" aria-label="회원 메뉴">{renderGuestItems()}</nav>}</>}
            </div>

            {user && <>
                <div className={`common-header-popover common-header-notification-popover${openPanel === "notifications" ? " is-open" : ""}`}>
                    <div className="common-header-popover-header"><div><strong>알림</strong>{unreadCount > 0 && <span>{unreadCount}개 읽지 않음</span>}</div><button type="button" onClick={markAllNotificationsRead} disabled={unreadCount === 0}>모두 읽음</button></div>
                    <div className="common-header-popover-body">{notificationLoading ? null : notifications.length === 0 ? <p className="common-header-popover-empty">새로운 알림이 없습니다.</p> : notifications.slice(0, 8).map((item) => { const actionable = item.groupId && ["QUEUE_TURN", "SEAT_HOLD_EXPIRED"].includes(item.type); return <article key={item.id} className={`common-header-notification-item${item.read ? "" : " is-unread"}${actionable ? " is-actionable" : ""}`} role={actionable ? "link" : undefined} tabIndex={actionable ? 0 : undefined} onClick={() => openNotification(item)} onKeyDown={(event) => { if ((event.key === "Enter" || event.key === " ") && actionable) { event.preventDefault(); openNotification(item); } }}><div><strong>{TYPE_LABELS[item.type] ?? "알림"}</strong><p>{item.message}</p><time>{formatDate(item.createdAt)}</time></div><div className="common-header-popover-actions">{!item.read && <button type="button" onClick={(event) => { event.stopPropagation(); markNotificationRead(item.id); }}>읽음</button>}<button type="button" onClick={(event) => { event.stopPropagation(); removeNotification(item.id); }}>삭제</button></div></article>; })}</div>
                </div>

                <div className={`common-header-popover common-header-ticket-popover${openPanel === "tickets" ? " is-open" : ""}`}>
                    <div className="common-header-popover-header"><div><strong>내 티켓</strong><span>{validTickets.length}개</span></div><Link to={PAGE_PATHS.tickets} className="common-header-ticket-list-link" onClick={handleNavigation}>목록</Link></div>
                    <div className="common-header-popover-body">{ticketLoading ? null : validTickets.length === 0 ? <p className="common-header-popover-empty">사용 가능한 티켓이 없습니다.</p> : validTickets.slice(0, 6).map((ticket) => <article key={ticket.ticketId} className="common-header-ticket-item common-header-ticket-item-valid" onClick={() => showTicketPreview(ticket)} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); showTicketPreview(ticket); } }} tabIndex={0} role="button"><div className="common-header-ticket-title"><strong>{ticket.movieTitle}</strong><span>사용 가능</span></div><p><InlineDetails items={[ticket.theaterName, ticket.screenName]} /></p><p>{formatDate(ticket.startTime)}</p><p>좌석: {ticket.seats?.join(", ") || "-"}</p><small>티켓 번호 {ticket.ticketNumber}</small></article>)}</div>
                </div>
            </>}

            {previewTicket && <HeaderTicketPreview ticket={previewTicket} onMouseEnter={keepTicketPreview} onMouseLeave={hideTicketPreview} onClose={closeTicketPreview} />}

            {menuOpen && <nav id="common-header-mobile-menu" className="common-header-mobile-menu" aria-label="전체 메뉴">{renderAccountItems()}</nav>}
        </header>
    );
}
