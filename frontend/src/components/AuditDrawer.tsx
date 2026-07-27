import { useQuery } from "@tanstack/react-query";
import { api, type AuditEvent } from "../api/client";
import { useI18n } from "../i18n/I18nProvider";
import { CloseIcon } from "./icons";

interface AuditDrawerProps {
  open: boolean;
  entityType: string;
  title?: string;
  emptyLabel?: string;
  onClose: () => void;
}

export function AuditDrawer({ open, entityType, title, emptyLabel = "No audited actions yet.", onClose }: AuditDrawerProps) {
  const { t, tp, formatDate } = useI18n();
  const auditQ = useQuery({ queryKey: ["audit", entityType], queryFn: () => api.auditEvents(entityType), enabled: open });
  if (!open) return null;
  return <div className="drawer-scrim" role="presentation" onMouseDown={onClose}>
    <aside className="audit-drawer" role="dialog" aria-modal="true" aria-label={`${entityType} ${tp("audit history")}`} onMouseDown={(event) => event.stopPropagation()}>
      <header className="drawer-head"><div><span className="eyebrow">{tp("Immutable evidence")}</span><h2>{tp(title ?? `${entityType} audit`)}</h2></div>
        <button className="icon-btn" onClick={onClose} aria-label={`${t("ui.common.close", "Close")} ${t("ui.grid.audit", "Audit")}`}><CloseIcon /></button></header>
      <div className="audit-list">
        {auditQ.isLoading && <p className="loading-note">{tp("Loading audit trail...")}</p>}
        {auditQ.isError && <p className="empty-note">{tp("Audit trail unavailable.")}</p>}
        {auditQ.data?.map((event: AuditEvent) => <article className="audit-event" key={event.id}>
          <div><strong>{tp(event.action.replace(/_/g, " "))}</strong><time>{formatDate(event.occurredAt, { dateStyle: "medium", timeStyle: "short" })}</time></div>
          <p>{tp(event.summary)}</p><small data-i18n-skip>{event.actorName} · {event.actorRole}</small>
        </article>)}
        {auditQ.data?.length === 0 && <p className="empty-note">{tp(emptyLabel)}</p>}
      </div>
    </aside>
  </div>;
}
