import { NavLink } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { initials } from "../lib/format";
import {
  AccountsIcon,
  HomeIcon,
  LeadsIcon,
  LogoutIcon,
  PipelineIcon,
  ReferenceIcon,
} from "./icons";

const NAV = [
  { to: "/", label: "Home", icon: HomeIcon, end: true },
  { to: "/pipeline", label: "Pipeline", icon: PipelineIcon, end: false },
  { to: "/accounts", label: "Accounts", icon: AccountsIcon, end: false },
  { to: "/leads", label: "Leads", icon: LeadsIcon, end: false },
  { to: "/reference-data", label: "Reference Data", icon: ReferenceIcon, end: false },
];

export function CommandRail({ open, onNavigate }: { open: boolean; onNavigate: () => void }) {
  const { user, logout } = useAuth();

  return (
    <nav className={`rail${open ? " rail-open" : ""}`} aria-label="Primary">
      <div className="rail-brand">
        <img className="rail-logo" src="/axiom.svg" alt="" />
        <span><strong>AXIOM</strong><small>Revenue OS</small></span>
      </div>
      <span className="rail-section">Command</span>
      {NAV.map(({ to, label, icon: Icon, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          title={label}
          aria-label={label}
          className={({ isActive }) => `rail-btn${isActive ? " active" : ""}`}
          onClick={onNavigate}
        >
          <Icon />
          <span>{label}</span>
        </NavLink>
      ))}
      <div className="rail-spacer" />
      <button
        className="rail-btn"
        title="Sign out"
        aria-label="Sign out"
        onClick={logout}
      >
        <LogoutIcon />
        <span>Sign out</span>
      </button>
      <div className="rail-identity" title={user?.displayName ?? ""}>
        <div className="rail-avatar">{initials(user?.displayName)}</div>
        <span><strong>{user?.displayName ?? "Operator"}</strong><small>{user?.role ?? "User"}</small></span>
      </div>
    </nav>
  );
}
