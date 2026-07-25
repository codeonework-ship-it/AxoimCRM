import { useState } from "react";
import { NavLink } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useT, type TranslateFn } from "../i18n/I18nProvider";
import { ChevronIcon } from "./icons";
import { NAV_BADGE_KEYS, groupsForRole, type ModuleItem } from "./navigation";

/**
 * Primary navigation over the whole product module map.
 *
 * Modules that are specified but not yet built render as disabled with a
 * "Planned" marker rather than being hidden. Hiding them would make the sidebar
 * a misleading picture of the product; showing them as live links would be
 * worse. A visible, inert entry is the honest option and doubles as a roadmap
 * the whole team shares.
 */
function ModuleLink({ item, onNavigate, t }: { item: ModuleItem; onNavigate: () => void; t: TranslateFn }) {
  const Icon = item.icon;
  // Every label goes through the registry with the English literal from
  // navigation.ts as its fallback, so an untranslated key never blanks a nav row.
  const label = t(item.labelKey, item.label);

  if (item.status === "planned") {
    return (
      <span
        className="rail-btn is-planned"
        aria-disabled="true"
        title={`${label} — ${t(NAV_BADGE_KEYS.plannedTitle, "planned, not yet built")} (${item.epic})`}
      >
        <Icon />
        <span>{label}</span>
        <em className="rail-flag">{t(NAV_BADGE_KEYS.planned, "Planned")}</em>
      </span>
    );
  }

  return (
    <NavLink
      to={item.to}
      end={item.end}
      title={label}
      className={({ isActive }) => `rail-btn${isActive ? " active" : ""}`}
      onClick={onNavigate}
    >
      <Icon />
      <span>{label}</span>
      {item.status === "partial" && (
        <em className="rail-flag is-partial" title={t(NAV_BADGE_KEYS.betaTitle, "Partially implemented")}>
          {t(NAV_BADGE_KEYS.beta, "Beta")}
        </em>
      )}
    </NavLink>
  );
}

export function CommandRail({ open, onNavigate }: { open: boolean; onNavigate: () => void }) {
  // Identity and sign-out moved to the header UserMenu; the rail is navigation only.
  const { user } = useAuth();
  const t = useT();
  const groups = groupsForRole(user?.role);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const toggle = (id: string) =>
    setCollapsed((prev) => ({ ...prev, [id]: !prev[id] }));

  return (
    <nav className={`rail${open ? " rail-open" : ""}`} aria-label="Primary">
      <div className="rail-brand">
        <img className="rail-logo" src="/axiom.svg" alt="" />
        <span><strong>AXIOM</strong><small>Revenue OS</small></span>
      </div>

      <div className="rail-scroll">
        {groups.map((group) => {
          const isCollapsed = collapsed[group.id] ?? false;
          const panelId = `rail-group-${group.id}`;
          return (
            <section key={group.id} className="rail-group">
              <button
                type="button"
                className={`rail-section${isCollapsed ? " is-collapsed" : ""}`}
                aria-expanded={!isCollapsed}
                aria-controls={panelId}
                onClick={() => toggle(group.id)}
              >
                <ChevronIcon size={12} />
                {t(group.labelKey, group.label)}
              </button>
              <div id={panelId} className="rail-group-items" hidden={isCollapsed}>
                {group.items.map((item) => (
                  <ModuleLink key={item.to} item={item} onNavigate={onNavigate} t={t} />
                ))}
              </div>
            </section>
          );
        })}
      </div>

    </nav>
  );
}
