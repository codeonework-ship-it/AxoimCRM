import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { api } from "../api/client";
import { useT } from "../i18n/I18nProvider";
import { LocaleSwitcher } from "../i18n/LocaleSwitcher";
import { useNotifications } from "./notifications";
import { UserMenu } from "./UserMenu";
import { BellIcon, MenuIcon, SearchIcon, SunMoonIcon } from "./icons";

function toggleTheme(): "light" | "dark" {
  const root = document.documentElement;
  const prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  const current =
    root.dataset.theme ?? (prefersDark ? "dark" : "light");
  const next = current === "dark" ? "light" : "dark";
  root.dataset.theme = next;
  localStorage.setItem("axiom.theme", next);
  return next;
}

interface TopBarProps {
  onOpenCommand: () => void;
  onOpenHelp: () => void;
  onToggleNav: () => void;
  navExpanded: boolean;
}

export function TopBar({ onOpenCommand, onOpenHelp, onToggleNav, navExpanded }: TopBarProps) {
  const navigate = useNavigate();
  const t = useT();
  const { user, tenant, switchTenant } = useAuth();
  const tenantsQ = useQuery({
    queryKey: ["auth", "tenants"],
    queryFn: api.tenants,
    enabled: !!user?.platformUser,
    staleTime: 60_000,
  });
  const switchMutation = useMutation({ mutationFn: switchTenant });
  const { notifications, unreadCount, isLoading, isError, markRead, markUnread, markAllRead, refetch } =
    useNotifications();
  const [panelOpen, setPanelOpen] = useState(false);
  const [filter, setFilter] = useState<"all" | "unread">("all");
  const [theme, setTheme] = useState<"light" | "dark">(() => {
    if (document.documentElement.dataset.theme === "light") return "light";
    if (document.documentElement.dataset.theme === "dark") return "dark";
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  });
  const bellWrapRef = useRef<HTMLDivElement>(null);
  const bellButtonRef = useRef<HTMLButtonElement>(null);

  // Click-away closes the notification popover.
  useEffect(() => {
    if (!panelOpen) return;
    const onDown = (e: MouseEvent) => {
      if (!bellWrapRef.current?.contains(e.target as Node)) {
        setPanelOpen(false);
      }
    };
    window.addEventListener("mousedown", onDown);
    return () => window.removeEventListener("mousedown", onDown);
  }, [panelOpen]);

  useEffect(() => {
    if (!panelOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setPanelOpen(false);
        bellButtonRef.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [panelOpen]);

  const isMac = navigator.platform.toUpperCase().includes("MAC");
  const visibleNotifications = filter === "unread"
    ? notifications.filter((notification) => !notification.read)
    : notifications;

  return (
    <header className="topbar">
      <button
        className="icon-btn mobile-menu"
        aria-label={t("shell.toggleSidebar", "Toggle sidebar")}
        aria-expanded={navExpanded}
        title={t("shell.toggleSidebar", "Toggle sidebar")}
        onClick={onToggleNav}
      ><MenuIcon /></button>
      <div className="topbar-context">
        <span className="topbar-title">Revenue operations</span>
        {user?.platformUser && tenantsQ.data ? (
          <label className="workspace-switch"><span className="sr-only">Active tenant</span><select
            value={tenant?.slug ?? ""}
            disabled={switchMutation.isPending}
            onChange={(event) => switchMutation.mutate(event.target.value)}
          >{tenantsQ.data.map((option) => <option key={option.id} value={option.slug}>{option.name}</option>)}</select></label>
        ) : <span className="topbar-tenant">{tenant?.name ?? "—"} · Live workspace</span>}
      </div>

      <button className="search-box" onClick={onOpenCommand} aria-label={t("shell.search", "Search or run a command")}>
        <SearchIcon size={16} />
        <span>{t("shell.search", "Search or run a command")}</span>
        <span className="search-kbd">{isMac ? "⌘K" : "Ctrl K"}</span>
      </button>

      <div ref={bellWrapRef} style={{ position: "relative" }}>
        <button
          ref={bellButtonRef}
          className="icon-btn"
          // "label: count" rather than "count label". Russian declines the noun
          // after a numeral (1 непрочитанное / 2 непрочитанных / 5
          // непрочитанных), and this layer has no plural-rule machinery. A
          // labelled count sidesteps agreement entirely and reads correctly in
          // all three languages.
          aria-label={`${t("shell.notifications", "Notifications")}${unreadCount ? ` (${t("shell.unread", "unread")}: ${unreadCount})` : ""}`}
          title={t("shell.notifications", "Notifications")}
          aria-expanded={panelOpen}
          onClick={() => setPanelOpen((o) => !o)}
        >
          <BellIcon />
          {unreadCount > 0 && <span className="bell-dot" aria-hidden />}
          {unreadCount > 0 && <span className="bell-count" aria-hidden>{unreadCount}</span>}
        </button>

        {panelOpen && (
          <div className="notif-pop" role="dialog" aria-label={t("shell.notifications", "Notifications")}>
            <div className="notif-head">
              <span><span className="eyebrow">Signal center</span><strong>Notifications</strong></span>
              {unreadCount > 0 && (
                <button className="link-btn" onClick={markAllRead}>
                  Mark all read
                </button>
              )}
            </div>
            <div className="notif-tabs" role="tablist" aria-label="Notification filter">
              <button role="tab" aria-selected={filter === "all"} onClick={() => setFilter("all")}>All</button>
              <button role="tab" aria-selected={filter === "unread"} onClick={() => setFilter("unread")}>Unread {unreadCount}</button>
            </div>
            <div className="notif-list">
              {isLoading && <p className="notif-empty">Synchronizing signal feed…</p>}
              {isError && (
                <div className="notif-error" role="alert">
                  <strong>Signal feed unavailable</strong>
                  <span>Your CRM data is unaffected.</span>
                  <button className="link-btn" onClick={refetch}>Retry</button>
                </div>
              )}
              {visibleNotifications.map((n) => (
                <article key={n.id} className={`notif-item${n.read ? " read" : ""}`}>
                  <button
                    className="notif-main"
                    onClick={() => {
                      markRead(n.id);
                      if (n.href) navigate(n.href);
                      setPanelOpen(false);
                    }}
                  >
                    <span className={`dot dot-${n.kind}`} aria-hidden />
                    <span>
                      <span className="notif-meta"><span>{n.kind}</span><time>{n.time}</time></span>
                      <span className="notif-title">{n.title}</span>
                      <span className="notif-body">{n.body}</span>
                      <span className="notif-reason">Why: {n.reason}</span>
                      {n.actionRequired && !n.actionCompleted && <span className="notif-action-tag">Action required</span>}
                    </span>
                  </button>
                  <button className="notif-toggle" onClick={() => n.read ? markUnread(n.id) : markRead(n.id)}>
                    Mark {n.read ? "unread" : "read"}
                  </button>
                </article>
              ))}
              {!isLoading && !isError && visibleNotifications.length === 0 && <p className="notif-empty">Signal queue clear. You are caught up.</p>}
            </div>
            <footer className="notif-footer">Access-scoped live feed · refreshes every minute</footer>
          </div>
        )}
      </div>

      <button
        className="manual-button help-button"
        aria-label={t("shell.manual", "User Manual")}
        title={`${t("shell.manual", "User Manual")} (Ctrl+/)`}
        onClick={onOpenHelp}
      >
        {t("shell.manual", "User Manual")}
      </button>

      <LocaleSwitcher />

      <button
        className="icon-btn"
        aria-label={t("shell.theme", "Toggle theme")}
        aria-pressed={theme === "dark"}
        title={t("shell.theme", "Toggle theme")}
        onClick={() => setTheme(toggleTheme())}
      >
        <SunMoonIcon />
      </button>

      {/* Identity and sign-out live top-right — the conventional place, and
          visible even when the rail is collapsed to icons. */}
      <UserMenu />
    </header>
  );
}
