import { useQuery } from "@tanstack/react-query";
import { api, type AuditEvent } from "../api/client";
import { formatDate } from "../lib/format";
import { CloseIcon } from "./icons";

interface AuditDrawerProps {
  open: boolean;
  entityType: string;
  title?: string;
  emptyLabel?: string;
  onClose: () => void;
}

export function AuditDrawer({ open, entityType, title, emptyLabel = "No audited actions yet.", onClose }: AuditDrawerProps) {
  const auditQ = useQuery({ queryKey: ["audit", entityType], queryFn: () => api.auditEvents(entityType), enabled: open });
  if (!open) return null;
  return <div className="drawer-scrim" role="presentation" onMouseDown={onClose}>
    <aside className="audit-drawer" role="dialog" aria-modal="true" aria-label={`${entityType} audit history`} onMouseDown={(event) => event.stopPropagation()}>
      <header className="drawer-head"><div><span className="eyebrow">Immutable evidence</span><h2>{title ?? `${entityType} audit`}</h2></div>
        <button className="icon-btn" onClick={onClose} aria-label="Close audit"><CloseIcon /></button></header>
      <div className="audit-list">
        {auditQ.isLoading && <p className="loading-note">Loading audit trail...</p>}
        {auditQ.isError && <p className="empty-note">Audit trail unavailable.</p>}
        {auditQ.data?.map((event: AuditEvent) => <article className="audit-event" key={event.id}>
          <div><strong>{event.action.replace(/_/g, " ")}</strong><time>{formatDate(event.occurredAt)}</time></div>
          <p>{event.summary}</p><small>{event.actorName} · {event.actorRole}</small>
        </article>)}
        {auditQ.data?.length === 0 && <p className="empty-note">{emptyLabel}</p>}
      </div>
    </aside>
  </div>;
}
