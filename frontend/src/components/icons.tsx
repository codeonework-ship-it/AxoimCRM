/** Minimal inline icon set — 1.5px strokes, squared joints, AEGIS style. */

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
