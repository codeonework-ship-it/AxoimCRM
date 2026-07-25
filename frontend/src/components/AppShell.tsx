import { useEffect, useState } from "react";
import { Outlet, useNavigate } from "react-router-dom";
import { CommandRail } from "./CommandRail";
import { TopBar } from "./TopBar";
import { NotificationsProvider } from "./notifications";
import { CommandPalette } from "./CommandPalette";
import { HelpDrawer } from "./HelpDrawer";

export function AppShell() {
  const navigate = useNavigate();
  const [commandOpen, setCommandOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [navOpen, setNavOpen] = useState(false);
  const [railCollapsed, setRailCollapsed] = useState(false);

  useEffect(() => {
    let goPrefix = false;
    let prefixTimer = 0;
    const onKey = (event: KeyboardEvent) => {
      const tag = (event.target as HTMLElement | null)?.tagName;
      const isTyping = tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setCommandOpen(true);
        return;
      }
      if ((event.metaKey || event.ctrlKey) && event.key === "/") {
        event.preventDefault();
        setHelpOpen(true);
        return;
      }
      if (event.key === "Escape") {
        setCommandOpen(false);
        setHelpOpen(false);
        setNavOpen(false);
        return;
      }
      if (isTyping || event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.key.toLowerCase() === "g") {
        goPrefix = true;
        window.clearTimeout(prefixTimer);
        prefixTimer = window.setTimeout(() => { goPrefix = false; }, 900);
        return;
      }
      if (goPrefix) {
        const routes: Record<string, string> = { h: "/", p: "/pipeline", a: "/accounts", l: "/leads" };
        const route = routes[event.key.toLowerCase()];
        goPrefix = false;
        if (route) navigate(route);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.clearTimeout(prefixTimer);
    };
  }, [navigate]);

  return (
    <NotificationsProvider>
      <div className={`shell${railCollapsed ? " rail-collapsed" : ""}`}>
        <CommandRail open={navOpen} onNavigate={() => setNavOpen(false)} />
        <div className="shell-main">
          <TopBar
            onOpenCommand={() => setCommandOpen(true)}
            onOpenHelp={() => setHelpOpen(true)}
            onToggleNav={() => {
              if (window.matchMedia("(max-width: 900px)").matches) setNavOpen((open) => !open);
              else setRailCollapsed((collapsed) => !collapsed);
            }}
            navExpanded={navOpen || !railCollapsed}
          />
          <main className="shell-content app-ground">
            <Outlet />
          </main>
        </div>
      </div>
      {navOpen && <button className="nav-scrim" aria-label="Close navigation" onClick={() => setNavOpen(false)} />}
      <CommandPalette open={commandOpen} onClose={() => setCommandOpen(false)} onOpenHelp={() => setHelpOpen(true)} />
      <HelpDrawer open={helpOpen} onClose={() => setHelpOpen(false)} />
    </NotificationsProvider>
  );
}
