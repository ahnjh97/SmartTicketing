import "../styles/CommonHeader.css";
import { PAGE_PATHS } from "../navigation.js";
import { NavLink } from "react-router-dom";

export default function CommonHeader({
    user, disabled, setupRequired, onLogout,
}) {
    const accountItems = user
        ? [{ view: "profile", label: "회원정보" }, { view: "preferences", label: "선호 정보" }]
        : [{ view: "login", label: "로그인" }, { view: "signup", label: "회원가입" }];

    const navigationDisabled = disabled || Boolean(user && setupRequired);

    function handleNavigation(event) {
        if (navigationDisabled) {
            event.preventDefault();
            return;
        }
    }

    return (
        <header className="common-header">
            <div className="common-header-inner">
                <NavLink
                    to={PAGE_PATHS.home}
                    end
                    className="common-header-brand"
                    aria-disabled={navigationDisabled || undefined}
                    tabIndex={navigationDisabled ? -1 : undefined}
                    onClick={handleNavigation}
                >
                    SmartTicketing
                </NavLink>
                <nav className="common-header-nav" aria-label="주 메뉴">
                    {[{ view: "movies", label: "영화" }, { view: "theaters", label: "극장" }].map(({ view, label }) => (
                        <NavLink
                            key={view}
                            to={PAGE_PATHS[view]}
                            className="common-header-link"
                            aria-disabled={navigationDisabled || undefined}
                            tabIndex={navigationDisabled ? -1 : undefined}
                            onClick={handleNavigation}
                        >
                            {label}
                        </NavLink>
                    ))}
                </nav>
                <nav className="common-header-account" aria-label="회원 메뉴">
                    {accountItems.map(({ view, label }) => (
                        <NavLink
                            key={view}
                            to={PAGE_PATHS[view]}
                            className={`common-header-link${view === "signup" ? " common-header-signup" : ""}`}
                            aria-disabled={navigationDisabled || undefined}
                            tabIndex={navigationDisabled ? -1 : undefined}
                            onClick={handleNavigation}
                        >
                            {label}
                        </NavLink>
                    ))}
                    {user && (
                        <button
                            type="button"
                            className="common-header-link"
                            disabled={disabled}
                            onClick={onLogout}
                        >
                            로그아웃
                        </button>
                    )}
                </nav>
            </div>
        </header>
    );
}
