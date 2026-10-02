import glass from './GlassButton.module.css';
import "../styles/CommonHeader.css";
import { PAGE_PATHS } from "../navigation.js";
import { Link, matchPath, NavLink, useLocation } from "react-router-dom";
import { useEffect, useRef, useState } from "react";
import { notificationApi } from "../api/notifications.js";

export default function CommonHeader({
    user, disabled, setupRequired, onLogout,
}) {
    const { pathname, key: locationKey } = useLocation();
    const headerRef = useRef(null);
    const innerRef = useRef(null);
    const brandRef = useRef(null);
    const navRef = useRef(null);
    const accountRef = useRef(null);
    const toggleRef = useRef(null);
    const [compact, setCompact] = useState(false);
    const [openLocation, setOpenLocation] = useState(null);
    const [unreadCount, setUnreadCount] = useState(0);
    const menuOpen = compact && openLocation === locationKey;
    const activeMenu = pathname === PAGE_PATHS.home || matchPath(`${PAGE_PATHS.movies}/*`, pathname)
        ? "movies"
        : matchPath(`${PAGE_PATHS.theaters}/*`, pathname) ? "theaters" : null;
    const accountItems = user
        ? [{ view: "notifications", label: "알림" }, { view: "tickets", label: "내 티켓" }, { view: "logout", label: "로그아웃" }, { view: "profile", label: "마이페이지" }]
        : [{ view: "login", label: "로그인" }, { view: "signup", label: "회원가입" }];

    const navigationDisabled = disabled || Boolean(user && setupRequired);

    useEffect(() => {
        if (!user || disabled) {
            setUnreadCount(0);
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
            if (!needsCompact) setOpenLocation(null);
        });
        [innerRef, brandRef, navRef, accountRef]
            .map((ref) => ref.current)
            .filter(Boolean)
            .forEach((element) => observer.observe(element));
        return () => observer.disconnect();
    }, []);

    useEffect(() => {
        if (!menuOpen) return;
        function closeOnOutsideClick(event) {
            if (!headerRef.current.contains(event.target)) setOpenLocation(null);
        }
        function closeOnEscape(event) {
            if (event.key === "Escape") {
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
    }, [menuOpen]);

    function handleNavigation(event) {
        if (navigationDisabled) {
            event.preventDefault();
            return;
        }
        setOpenLocation(null);
    }

    function handleLogout() {
        setOpenLocation(null);
        onLogout();
    }

    function renderAccountItems() {
        return accountItems.map(({ view, label }) => (
            view === "logout"
                ? <button key={view} type="button" className={`common-header-link ${glass.button}`}
                    disabled={disabled} onClick={handleLogout}>{label}</button>
                : <NavLink key={view} to={PAGE_PATHS[view]}
                className={`common-header-link${view === "signup" ? " common-header-signup" : ` ${glass.button}`}`}
                aria-disabled={navigationDisabled || undefined}
                tabIndex={navigationDisabled ? -1 : undefined} onClick={handleNavigation}>
                {label}
            </NavLink>
        ));
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
                    {renderAccountItems()}
                </nav>
                </div>
                {compact && <>
                    <nav className="common-header-nav common-header-compact-nav" aria-label="주 메뉴">
                        {[{ view: "movies", label: "영화" }, { view: "theaters", label: "극장" }].map(({ view, label }) => (
                        <Link key={view} to={PAGE_PATHS[view]}
                            className={`common-header-link common-header-menu-link${activeMenu === view ? " is-active" : ""}`}
                            aria-current={activeMenu === view ? "page" : undefined}
                            aria-disabled={navigationDisabled || undefined}
                            tabIndex={navigationDisabled ? -1 : undefined} onClick={handleNavigation}>{label}</Link>
                        ))}
                    </nav>
                    <button ref={toggleRef} type="button" className="common-header-menu-toggle"
                        aria-label={menuOpen ? "메뉴 닫기" : "메뉴 열기"}
                        aria-expanded={menuOpen} aria-controls="common-header-mobile-menu"
                        onClick={() => setOpenLocation(menuOpen ? null : locationKey)}>
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                            {menuOpen ? <path d="m6 6 12 12M18 6 6 18" /> : <path d="M4 6h16M4 12h16M4 18h16" />}
                        </svg>
                    </button>
                </>}
            </div>
            {menuOpen && <nav id="common-header-mobile-menu" className="common-header-mobile-menu" aria-label="전체 메뉴">
                {renderAccountItems()}
            </nav>}
        </header>
    );
}
