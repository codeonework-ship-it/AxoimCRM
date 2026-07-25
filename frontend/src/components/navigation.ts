import type { ComponentType } from "react";
import {
  AccountsIcon,
  AiIcon,
  AuditIcon,
  AutomationIcon,
  BfsiIcon,
  BellIcon,
  CampaignIcon,
  CaseIcon,
  CommodityIcon,
  ContractIcon,
  ForecastIcon,
  HomeIcon,
  IntegrationIcon,
  LeadsIcon,
  MigrationIcon,
  MobileIcon,
  PartnerIcon,
  PipelineIcon,
  ProductIcon,
  QuoteIcon,
  ReferenceIcon,
  SparkIcon,
} from "./icons";

/**
 * The full product module map, grouped the way a revenue org actually thinks
 * about its day rather than the way the backlog is numbered.
 *
 * Every module in the product appears here, including the ones not yet built.
 * That is deliberate: a sidebar that silently omits planned modules makes the
 * product look smaller than it is and gives the team no shared map. Instead,
 * unbuilt modules render as visibly inactive with a "Planned" badge, so nobody
 * mistakes a roadmap item for a broken link.
 *
 * `epic` ties each entry back to docs/product/05-epics-and-stories.md so the
 * navigation and the backlog cannot drift apart unnoticed.
 *
 * `labelKey` addresses the i18n registry (i18n.translation_key.key_path, seeded
 * in V10__i18n_translation_registry.sql). `label` stays as the English literal
 * and is passed to `t()` as the fallback, so this file remains readable on its
 * own and a missing translation row degrades to English rather than to a raw
 * key path. Keys are required, not optional — an untranslatable nav entry is a
 * bug, and making the field mandatory means the compiler catches a new module
 * that forgot one.
 */

export type ModuleStatus = "ready" | "partial" | "planned";

export interface ModuleItem {
  label: string;
  /** i18n key in the translation registry; `label` is the English fallback. */
  labelKey: string;
  to: string;
  icon: ComponentType<{ size?: number }>;
  epic: string;
  status: ModuleStatus;
  /** Roles that may see the entry. Omitted means every signed-in role. */
  roles?: string[];
  end?: boolean;
}

export interface ModuleGroup {
  id: string;
  label: string;
  /** i18n key in the translation registry; `label` is the English fallback. */
  labelKey: string;
  items: ModuleItem[];
}

/** Badge and chrome keys, so call sites cannot typo a key path. */
export const NAV_BADGE_KEYS = {
  planned: "nav.badge.planned",
  beta: "nav.badge.beta",
  plannedTitle: "nav.badge.plannedTitle",
  betaTitle: "nav.badge.betaTitle",
} as const;

/** Platform-scoped roles see the operator sections. */
export const PLATFORM_ROLES = ["SUPER_ADMIN", "SUPER_AUDIT"];
const ADMIN_ROLES = ["SUPER_ADMIN", "SUPER_AUDIT", "TENANT_ADMIN"];
const GOVERNANCE_ROLES = [...ADMIN_ROLES, "DATA_STEWARD", "AUDITOR", "OPERATIONS"];

export const MODULE_GROUPS: ModuleGroup[] = [
  {
    id: "workspace",
    label: "Workspace",
    labelKey: "nav.group.workspace",
    items: [
      { label: "Home", labelKey: "nav.module.home", to: "/", icon: HomeIcon, epic: "E15", status: "ready", end: true },
      { label: "Activities", labelKey: "nav.module.activities", to: "/activities", icon: BellIcon, epic: "E07", status: "partial" },
    ],
  },
  {
    id: "sell",
    label: "Sell",
    labelKey: "nav.group.sell",
    items: [
      { label: "Leads", labelKey: "nav.module.leads", to: "/leads", icon: LeadsIcon, epic: "E05", status: "partial" },
      { label: "Pipeline", labelKey: "nav.module.pipeline", to: "/pipeline", icon: PipelineIcon, epic: "E06", status: "partial" },
      { label: "Accounts", labelKey: "nav.module.accounts", to: "/accounts", icon: AccountsIcon, epic: "E04", status: "partial" },
      { label: "Forecast", labelKey: "nav.module.forecast", to: "/forecast", icon: ForecastIcon, epic: "E10", status: "partial" },
    ],
  },
  {
    id: "commerce",
    label: "Quote to cash",
    labelKey: "nav.group.commerce",
    items: [
      { label: "Products", labelKey: "nav.module.products", to: "/products", icon: ProductIcon, epic: "E08", status: "partial" },
      { label: "Price Books", labelKey: "nav.module.priceBooks", to: "/price-books", icon: ProductIcon, epic: "E08", status: "partial" },
      { label: "Quotes & CPQ", labelKey: "nav.module.quotes", to: "/quotes", icon: QuoteIcon, epic: "E08", status: "partial" },
      { label: "Contracts", labelKey: "nav.module.contracts", to: "/contracts", icon: ContractIcon, epic: "E09", status: "partial" },
    ],
  },
  {
    id: "engage",
    label: "Engage & serve",
    labelKey: "nav.group.engage",
    items: [
      { label: "Campaigns", labelKey: "nav.module.campaigns", to: "/campaigns", icon: CampaignIcon, epic: "E11", status: "partial" },
      { label: "Cases", labelKey: "nav.module.cases", to: "/cases", icon: CaseIcon, epic: "E12", status: "partial" },
      { label: "Partners", labelKey: "nav.module.partners", to: "/partners", icon: PartnerIcon, epic: "E13", status: "planned" },
    ],
  },
  {
    id: "intelligence",
    label: "Intelligence",
    labelKey: "nav.group.intelligence",
    items: [
      { label: "Reports", labelKey: "nav.module.reports", to: "/reports", icon: SparkIcon, epic: "E15", status: "partial" },
      { label: "AI Copilot", labelKey: "nav.module.copilot", to: "/copilot", icon: AiIcon, epic: "E16", status: "planned" },
    ],
  },
  {
    id: "verticals",
    label: "Vertical packs",
    labelKey: "nav.group.verticals",
    items: [
      { label: "BFSI", labelKey: "nav.module.bfsi", to: "/packs/bfsi", icon: BfsiIcon, epic: "E22", status: "planned" },
      { label: "Commodity", labelKey: "nav.module.commodity", to: "/packs/commodity", icon: CommodityIcon, epic: "E23", status: "planned" },
    ],
  },
  {
    id: "platform",
    label: "Platform",
    labelKey: "nav.group.platform",
    items: [
      { label: "Reference Data", labelKey: "nav.module.referenceData", to: "/reference-data", icon: ReferenceIcon, epic: "E03", status: "partial", roles: GOVERNANCE_ROLES },
      { label: "Automation", labelKey: "nav.module.automation", to: "/automation", icon: AutomationIcon, epic: "E14", status: "planned", roles: ADMIN_ROLES },
      { label: "Integrations", labelKey: "nav.module.integrations", to: "/integrations", icon: IntegrationIcon, epic: "E17", status: "planned", roles: ADMIN_ROLES },
      { label: "Migration", labelKey: "nav.module.migration", to: "/migration", icon: MigrationIcon, epic: "E18", status: "partial", roles: ADMIN_ROLES },
      { label: "Mobile", labelKey: "nav.module.mobile", to: "/mobile", icon: MobileIcon, epic: "E21", status: "planned", roles: ADMIN_ROLES },
    ],
  },
  {
    id: "governance",
    label: "Governance",
    labelKey: "nav.group.governance",
    items: [
      { label: "Administration", labelKey: "nav.module.administration", to: "/admin", icon: ReferenceIcon, epic: "E19", status: "partial", roles: ADMIN_ROLES },
      { label: "Audit & Compliance", labelKey: "nav.module.audit", to: "/audit", icon: AuditIcon, epic: "E20", status: "planned", roles: GOVERNANCE_ROLES },
    ],
  },
];

/** Filters the map down to what this role may see. */
export function groupsForRole(role: string | undefined): ModuleGroup[] {
  const actual = role ?? "";
  return MODULE_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => !item.roles || item.roles.includes(actual)),
  })).filter((group) => group.items.length > 0);
}

/** Flat list of navigable (built) destinations — used by the command palette. */
export function navigableModules(role: string | undefined): ModuleItem[] {
  return groupsForRole(role)
    .flatMap((group) => group.items)
    .filter((item) => item.status !== "planned");
}
