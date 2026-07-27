/** Minimal inline icon set — 1.5px strokes, squared joints, Axiom style. */

interface IconProps {
  size?: number;
}

function base(size: number | undefined) {
  return {
    width: size ?? 18,
    height: size ?? 18,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.6,
    strokeLinecap: "square" as const,
    strokeLinejoin: "miter" as const,
    "aria-hidden": true,
  };
}

export function HomeIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M4 11 L12 4 L20 11 V20 H14 V15 H10 V20 H4 Z" />
    </svg>
  );
}

export function PipelineIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <rect x="3.5" y="4" width="4.5" height="16" />
      <rect x="9.75" y="4" width="4.5" height="11" />
      <rect x="16" y="4" width="4.5" height="7" />
    </svg>
  );
}

export function AccountsIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M4 20 V6 L10 3 V20 M10 20 H20 V9 H10 M13 12 H17 M13 15 H17" />
      <path d="M4 20 H20" />
    </svg>
  );
}

export function LeadsIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M3.5 20 C3.5 15.5 6 13.5 9 13.5 C12 13.5 14.5 15.5 14.5 20" />
      <path d="M17 6 V12 M14 9 H20" />
    </svg>
  );
}

export function ReferenceIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M4 5 H20 V10 H4 Z M4 14 H20 V19 H4 Z" />
      <path d="M8 5 V10 M8 14 V19 M13 7.5 H17 M13 16.5 H17" />
    </svg>
  );
}

/** Tenant data store used by the explicit page-loading boundary. */
export function DatabaseIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <ellipse cx="12" cy="5.5" rx="8" ry="3" />
      <path d="M4 5.5v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
      <path d="M4 11.5v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
    </svg>
  );
}

export function BellIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 4 C8.5 4 7 6.5 7 9.5 V14 L5 17 H19 L17 14 V9.5 C17 6.5 15.5 4 12 4 Z" />
      <path d="M10 20 H14" />
    </svg>
  );
}

export function SunMoonIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <circle cx="12" cy="12" r="4.5" />
      <path d="M12 2.5 V5 M12 19 V21.5 M2.5 12 H5 M19 12 H21.5 M5.3 5.3 L7 7 M17 17 L18.7 18.7 M18.7 5.3 L17 7 M7 17 L5.3 18.7" />
    </svg>
  );
}

export function LockIcon({ size }: IconProps) {
  return (
    <svg {...base(size ?? 12)}>
      <rect x="6" y="11" width="12" height="9" />
      <path d="M8.5 11 V7.5 C8.5 5.5 10 4 12 4 C14 4 15.5 5.5 15.5 7.5 V11" />
    </svg>
  );
}

export function LogoutIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M14 4 H5 V20 H14 M10 12 H21 M18 8.5 L21.5 12 L18 15.5" />
    </svg>
  );
}

export function SearchIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="M15.5 15.5 L21 21" />
    </svg>
  );
}

export function HelpIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <circle cx="12" cy="12" r="9" />
      <path d="M9.5 9 A2.7 2.7 0 1 1 13 11.6 C12.2 12 12 12.7 12 14" />
      <path d="M12 17.5 H12.01" />
    </svg>
  );
}

export function CloseIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M5 5 L19 19 M19 5 L5 19" />
    </svg>
  );
}

export function MenuIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M4 7 H20 M4 12 H20 M4 17 H20" />
    </svg>
  );
}

export function ArrowIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M5 12 H19 M14 7 L19 12 L14 17" />
    </svg>
  );
}

export function SparkIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 3 L13.7 9.3 L20 11 L13.7 12.7 L12 19 L10.3 12.7 L4 11 L10.3 9.3 Z" />
      <path d="M18.5 3.5 L19 5.5 L21 6 L19 6.5 L18.5 8.5 L18 6.5 L16 6 L18 5.5 Z" />
    </svg>
  );
}

/* ── Module glyphs ────────────────────────────────────────────────────────
   Each module in the navigation gets its own mark so the sidebar can be read
   by shape at a glance, not only by label. Same 1.5px squared-joint language
   as the core set above. */

export function ForecastIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M3 17l5-5 3 3 4-6 6 6" />
      <path d="M3 21h18" />
    </svg>
  );
}

export function QuoteIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M5 3h9l5 5v13H5z" />
      <path d="M14 3v5h5" />
      <path d="M9 13h6M9 17h4" />
    </svg>
  );
}

export function ContractIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M5 3h14v18H5z" />
      <path d="M9 8h6M9 12h6" />
      <path d="M9 16.5c1.5-1.5 3-1.5 4.5 0" />
    </svg>
  );
}

export function CampaignIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M4 10v4h4l6 4V6l-6 4z" />
      <path d="M18 9c1.2 1.2 1.2 4.8 0 6" />
    </svg>
  );
}

export function CaseIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M3 8h18v12H3z" />
      <path d="M9 8V5h6v3" />
      <path d="M3 13h18" />
    </svg>
  );
}

export function PartnerIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M8 11a3 3 0 100-6 3 3 0 000 6z" />
      <path d="M17 12a2.5 2.5 0 100-5 2.5 2.5 0 000 5z" />
      <path d="M2 20c0-3.3 2.7-5 6-5s6 1.7 6 5" />
      <path d="M16 15c3 0 6 1.3 6 5" />
    </svg>
  );
}

export function AutomationIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M5 5h5v5H5zM14 14h5v5h-5z" />
      <path d="M10 7.5h4v9h0" />
    </svg>
  );
}

export function IntegrationIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M9 3v6M15 3v6" />
      <path d="M6 9h12v4a6 6 0 01-12 0z" />
      <path d="M12 19v2" />
    </svg>
  );
}

export function MigrationIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M3 7h12" />
      <path d="M11 3l4 4-4 4" />
      <path d="M21 17H9" />
      <path d="M13 13l-4 4 4 4" />
    </svg>
  );
}

export function AuditIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />
      <path d="M9 12l2 2 4-4" />
    </svg>
  );
}

export function BfsiIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M3 9l9-5 9 5" />
      <path d="M5 9v9M10 9v9M14 9v9M19 9v9" />
      <path d="M3 21h18" />
    </svg>
  );
}

export function CommodityIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M3 15h4l3-8 4 12 3-6h4" />
    </svg>
  );
}

export function AiIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 3l1.8 4.7L18.5 9l-4.7 1.8L12 15.5l-1.8-4.7L5.5 9l4.7-1.3z" />
      <path d="M18 16l.9 2.1L21 19l-2.1.9L18 22l-.9-2.1L15 19l2.1-.9z" />
    </svg>
  );
}

export function MobileIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M7 2h10v20H7z" />
      <path d="M11 18h2" />
    </svg>
  );
}

export function ProductIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M12 2l9 5v10l-9 5-9-5V7z" />
      <path d="M3 7l9 5 9-5M12 12v10" />
    </svg>
  );
}

export function ChevronIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <path d="M9 5l7 7-7 7" />
    </svg>
  );
}

/** Language / locale. Meridians rather than a flag: a language is not a country. */
export function GlobeIcon({ size }: IconProps) {
  return (
    <svg {...base(size)}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M3.5 12h17" />
      <path d="M12 3.5c2.6 2.4 4 5.3 4 8.5s-1.4 6.1-4 8.5c-2.6-2.4-4-5.3-4-8.5s1.4-6.1 4-8.5z" />
    </svg>
  );
}
