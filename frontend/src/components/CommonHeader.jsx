import InlineDetails from './InlineDetails.jsx';
import { useEffect, useRef, useState } from "react";
import "../styles/CommonHeader.css";
import { PAGE_PATHS } from "../navigation.js";
import { Link, matchPath, NavLink, useLocation } from "react-router-dom";
import glass from "./GlassButton.module.css";
import { notificationApi } from "../api/notifications.js";
import { ticketApi } from "../api/tickets.js";

const TYPE_LABELS = {
    SEAT_HOLD_STARTED: "좌석 선점",
    QUEUE_TURN: "대기 순서",
    RESERVATION_COMPLETED: "예매 완료",
    RESERVATION_CANCELLED: "예매 취소",
    PAYMENT_FAILED: "결제 실패",
};

const TICKET_STATUS_LABELS = {
    VALID: "사용 가능",
    USED: "사용 완료",
    CANCELLED: "취소됨",
};

function formatDate(value) {
    return value ? new Date(value).toLocaleString("ko-KR") : "-";
}

export default function CommonHeader({
    user, disabled, setupRequired, onLogout,
}) {
    const { pathname } = useLocation();
    const headerRef = useRef(null);
    const innerRef = useRef(null);
    const brandRef = useRef(null);
    const navRef = useRef(null);
    const accountRef = useRef(null);
    const toggleRef = useRef(null);
    const [compact, setCompact] = useState(false);
    const [openLocation, setOpenLocation] = useState(null);
    const [openPanel, setOpenPanel] = useState(null);
    const [unreadCount, setUnreadCount] = useState(0);
    const [notifications, setNotifications] = useState([]);
    const [tickets, setTickets] = useState([]);
    const [notificationLoading, setNotificationLoading] = useState(false);
    const [ticketLoading, setTicketLoading] = useState(false);

    const menuOpen = compact && openLocation === pathname;
    const activeMenu = pathname === PAGE_PATHS.home || matchPath(`${PAGE_PATHS.movies}/*`, pathname)
        ? "movies"
        : matchPath(`${PAGE_PATHS.theaters}/*`, pathname) ? "theaters" : null;
    const navigationDisabled = disabled || Boolean(user && setupRequired);

    useEffect(() => {
        if (!user || disabled) {
            setUnreadCount(0);
            setNotifications([]);
            setTickets([]);
            setOpenPanel(null);
            return;
        }

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
        function renderGuestItems() {
        return (
            <>
                <NavLink
                    to={PAGE_PATHS.login}
                    className={`common-header-link ${glass.button}`}
                    onClick={handleNavigation}
                >
                    로그인
                </NavLink>
                <NavLink
                    to={PAGE_PATHS.signup}
                    className="common-header-link common-header-signup"
                    onClick={handleNavigation}
                >
                    회원가입
                </NavLink>
            </>
        );
    }

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
                if (target && !target.read) {
                    setUnreadCount((count) => Math.max(0, count - 1));
                }
                return items.map((item) => item.id === id ? { ...item, read: true } : item);
            });
        } catch {
            // 목록 UI는 유지하고 다음 갱신에서 다시 확인한다.
        }
    }

    async function markAllNotificationsRead() {
        try {
            await notificationApi.readAll();
            setNotifications((items) => items.map((item) => ({ ...item, read: true })));
            setUnreadCount(0);
        } catch {
            // 다음 폴링에서 다시 확인한다.
        }
    }

    async function removeNotification(id) {
        try {
            await notificationApi.delete(id);
            setNotifications((items) => {
                const target = items.find((item) => item.id === id);
                if (target && !target.read) {
                    setUnreadCount((count) => Math.max(0, count - 1));
                }
                return items.filter((item) => item.id !== id);
            });
        } catch {
            // 삭제 실패 시 현재 목록을 유지한다.
        }
    }

    function handleNavigation(event) {
        if (navigationDisabled) {
            event.preventDefault();
            return;
        }
        setOpenLocation(null);
        setOpenPanel(null);
    }

    function handleLogout() {
        setOpenLocation(null);
        setOpenPanel(null);
        onLogout();
    }

    function renderAccountItems() {
        return (
            <>
                <button
                    type="button"
                    className="common-header-icon-trigger"
                    aria-label={openPanel === "tickets" ? "내 티켓 닫기" : "내 티켓 열기"}
                    aria-expanded={openPanel === "tickets"}
                    onClick={() => togglePanel("tickets")}
                    disabled={navigationDisabled}
                >
                    <span className="common-header-trigger-icon" aria-hidden="true">
                        <svg viewBox="0 0 24 24" fill="none">
                            <path d="M4 7h16v10H4z" />
                            <path d="M8 7v10" stroke-dasharray="1.5 2" />
                            <path d="M16 7v10" stroke-dasharray="1.5 2" />
                        </svg>
                    </span>
                </button>

                <button
                    type="button"
                    className="common-header-notification-trigger"
                    aria-label={openPanel === "notifications" ? "알림 닫기" : "알림 열기"}
                    aria-expanded={openPanel === "notifications"}
                    onClick={() => togglePanel("notifications")}
                    disabled={navigationDisabled}
                >
                    <span className="common-header-trigger-icon" aria-hidden="true">
                        <svg viewBox="0 0 24 24" fill="none">
                            <path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9Z" />
                            <path d="M10 21h4" />
                        </svg>
                    </span>
                    {unreadCount > 0 && (
                        <span className="common-header-notification-badge">
                            {unreadCount > 99 ? "99+" : unreadCount}
                        </span>
                    )}
                </button>

                <button
                    type="button"
                    className="common-header-icon-trigger"
                    aria-label="로그아웃"
                    disabled={disabled}
                    onClick={handleLogout}
                >
                    <span className="common-header-trigger-icon" aria-hidden="true">
                        <svg viewBox="0 0 24 24" fill="none">
                            <path d="M10 17l5-5-5-5" />
                            <path d="M15 12H3" />
                            <path d="M13 5V3h7v18h-7v-2" />
                        </svg>
                    </span>
                </button>

                <NavLink
                    to={PAGE_PATHS.profile}
                    className="common-header-icon-trigger"
                    aria-label="마이페이지"
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
            </>
        );
    }

    return (
        <header ref={headerRef} className={`common-header${compact ? " common-header-compact" : ""}`}>
            <div ref={innerRef} className="common-header-inner">
                <NavLink
                    to={PAGE_PATHS.home}
                    end
                    className="common-header-brand"
                    ref={brandRef}
                    aria-disabled={navigationDisabled || undefined}
                    tabIndex={navigationDisabled ? -1 : undefined}
                    onClick={handleNavigation}
                >
                    SmartTicketing
                </NavLink>

                <div className="common-header-desktop" aria-hidden={compact || undefined} inert={compact || undefined}>
                    <nav ref={navRef} className="common-header-nav" aria-label="주 메뉴">
                        {[{ view: "movies", label: "영화" }, { view: "theaters", label: "극장" }].map(({ view, label }) => (
                            <Link
                                key={view}
                                to={PAGE_PATHS[view]}
                                className={`common-header-link common-header-menu-link${activeMenu === view ? " is-active" : ""}`}
                                aria-current={activeMenu === view ? "page" : undefined}
                                aria-disabled={navigationDisabled || undefined}
                                tabIndex={navigationDisabled ? -1 : undefined}
                                onClick={handleNavigation}
                            >
                                {label}
                            </Link>
                        ))}
                    </nav>
                    <nav ref={accountRef} className="common-header-account" aria-label="회원 메뉴">
                        {user ? renderAccountItems() : renderGuestItems()}
                    </nav>
                </div>

                {compact && <>
                    <nav className="common-header-nav common-header-compact-nav" aria-label="주 메뉴">
                        {[{ view: "movies", label: "영화" }, { view: "theaters", label: "극장" }].map(({ view, label }) => (
                            <Link key={view} to={PAGE_PATHS[view]}
                                className={`common-header-link common-header-menu-link${activeMenu === view ? " is-active" : ""}`}
                                aria-current={activeMenu === view ? "page" : undefined}
                                aria-disabled={navigationDisabled || undefined}
                                tabIndex={navigationDisabled ? -1 : undefined}
                                onClick={handleNavigation}>{label}</Link>
                        ))}
                    </nav>
                    <button ref={toggleRef} type="button" className="common-header-menu-toggle"
                        aria-label={menuOpen ? "메뉴 닫기" : "메뉴 열기"}
                        aria-expanded={menuOpen} aria-controls="common-header-mobile-menu"
                        onClick={() => {
                            setOpenPanel(null);
                            setOpenLocation(menuOpen ? null : pathname);
                        }}>
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                            {menuOpen ? <path d="m6 6 12 12M18 6 6 18" /> : <path d="M4 6h16M4 12h16M4 18h16" />}
                        </svg>
                    </button>
                </>}
            </div>

            {user && (
                <>
                    <div className={`common-header-popover common-header-notification-popover${openPanel === "notifications" ? " is-open" : ""}`}>
                        <div className="common-header-popover-header">
                            <div>
                                <strong>알림</strong>
                                {unreadCount > 0 && <span>{unreadCount}개 읽지 않음</span>}
                            </div>
                            <button type="button" onClick={markAllNotificationsRead} disabled={unreadCount === 0}>모두 읽음</button>
                        </div>
                        <div className="common-header-popover-body">
                            {notificationLoading ? (
                                <p className="common-header-popover-empty">알림을 불러오는 중입니다.</p>
                            ) : notifications.length === 0 ? (
                                <p className="common-header-popover-empty">새로운 알림이 없습니다.</p>
                            ) : (
                                notifications.slice(0, 8).map((item) => (
                                    <article key={item.id} className={`common-header-notification-item${item.read ? "" : " is-unread"}`}>
                                        <div>
                                            <strong>{TYPE_LABELS[item.type] ?? "알림"}</strong>
                                            <p>{item.message}</p>
                                            <time>{formatDate(item.createdAt)}</time>
                                        </div>
                                        <div className="common-header-popover-actions">
                                            {!item.read && <button type="button" onClick={() => markNotificationRead(item.id)}>읽음</button>}
                                            <button type="button" onClick={() => removeNotification(item.id)}>삭제</button>
                                        </div>
                                    </article>
                                ))
                            )}
                        </div>
                    </div>

                    <div className={`common-header-popover common-header-ticket-popover${openPanel === "tickets" ? " is-open" : ""}`}>
                        <div className="common-header-popover-header">
                            <div>
                                <strong>내 티켓</strong>
                                <span>{tickets.length}개</span>
                            </div>
                            <Link
                                to={PAGE_PATHS.tickets}
                                className="common-header-ticket-list-link"
                                onClick={handleNavigation}
                            >
                                목록
                            </Link>
                        </div>
                        <div className="common-header-popover-body">
                            {ticketLoading ? (
                                <p className="common-header-popover-empty">티켓을 불러오는 중입니다.</p>
                            ) : tickets.length === 0 ? (
                                <p className="common-header-popover-empty">발급된 티켓이 없습니다.</p>
                            ) : (
                                tickets.slice(0, 6).map((ticket) => (
                                    <article key={ticket.ticketId} className="common-header-ticket-item">
                                        <div className="common-header-ticket-title">
                                            <strong>{ticket.movieTitle}</strong>
                                            <span>{TICKET_STATUS_LABELS[ticket.status] ?? ticket.status}</span>
                                        </div>
                                        <p><InlineDetails items={[ticket.theaterName, ticket.screenName]} /></p>
                                        <p>{formatDate(ticket.startTime)}</p>
                                        <p>좌석: {ticket.seats?.join(", ") || "-"}</p>
                                        <small>티켓 번호 {ticket.ticketNumber}</small>
                                    </article>
                                ))
                            )}
                        </div>
                    </div>
                </>
            )}

            {menuOpen && <nav id="common-header-mobile-menu" className="common-header-mobile-menu" aria-label="전체 메뉴">
                {user ? renderAccountItems() : renderGuestItems()}
            </nav>}
        </header>
    );
}
