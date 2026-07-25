import { useEffect, useRef, useState } from "react";
import { useAuth } from "../auth/AuthContext";
import { useT } from "../i18n/I18nProvider";
import { initials } from "../lib/format";
import { ChevronIcon, LogoutIcon } from "./icons";

/**
 * Identity and sign-out, in the top-right of the header.
 *
 * This used to live pinned to the bottom of the navigation rail, which was the
 * wrong place for two reasons: it competed for attention with the module list
 * (the rail's actual job), and it broke the near-universal convention that
 * "who am I / how do I leave" sits top-right. It also disappeared entirely
 * when the rail was collapsed to icons.
 *
 * As a menu rather than a bare button, sign-out is now one deliberate step
 * behind a disclosure instead of a single mis-click next to navigation — while
 * the identity it belongs to stays visible at all times.
 */
export function UserMenu() {
  const { user, tenant, logout } = useAuth();
  const t = useT();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  // Click-away closes the menu.
  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (!wrapRef.current?.contains(event.target as Node)) setOpen(false);
    };
    window.addEventListener("mousedown", onDown);
    return () => window.removeEventListener("mousedown", onDown);
  }, [open]);

  // Escape closes and returns focus to the trigger, so keyboard users are not
  // stranded inside a dismissed popover.
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  const name = user?.displayName ?? "Operator";
  const role = user?.role ?? "USER";

  return (
    <div className="user-menu" ref={wrapRef}>
      <button
        ref={buttonRef}
        className="user-trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        title={`${name} · ${role}`}
        onClick={() => setOpen((o) => !o)}
      >
        <span className="user-avatar" aria-hidden>{initials(user?.displayName)}</span>
        <span className="user-id">
          <strong>{name}</strong>
          <small>{role}</small>
        </span>
        <ChevronIcon size={12} />
      </button>

      {open && (
        <div className="user-pop" role="menu" aria-label={t("shell.account", "Account")}>
          <div className="user-pop-head">
            <span className="user-avatar lg" aria-hidden>{initials(user?.displayName)}</span>
            <span>
              <strong>{name}</strong>
              {user?.email && <small>{user.email}</small>}
            </span>
          </div>
          <dl className="user-facts">
            <div>
              <dt>{t("shell.role", "Role")}</dt>
              <dd>{role}</dd>
            </div>
            <div>
              <dt>{t("shell.workspace", "Workspace")}</dt>
              <dd>{tenant?.name ?? "—"}</dd>
            </div>
          </dl>
          <button className="user-signout" role="menuitem" onClick={logout}>
            <LogoutIcon size={15} />
            {t("shell.signOut", "Sign out")}
          </button>
        </div>
      )}
    </div>
  );
}
