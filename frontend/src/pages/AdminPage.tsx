import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocation } from "react-router-dom";
import { api, isUnreachable, type ReportDefinition } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { useToasts } from "../components/Toasts";
import { formatDate, formatMoney } from "../lib/format";

const PLATFORM_ROLES = new Set(["SUPER_ADMIN", "SUPER_AUDIT"]);
const READ_ONLY_ROLES = new Set(["SUPER_AUDIT", "AUDITOR"]);
const TENANT_ROLES = ["TENANT_ADMIN", "SALES_MANAGER", "SALES", "MARKETING", "SERVICE", "OPERATIONS", "FINANCE", "DATA_STEWARD", "AUDITOR", "INTEGRATION"];

function csv(value: string): string[] {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

function HtmlEditor({ value, onChange }: { value: string; onChange: (next: string) => void }) {
  return (
    <div
      className="rich-editor"
      contentEditable
      role="textbox"
      aria-label="Rich text mail body"
      dangerouslySetInnerHTML={{ __html: value }}
      onInput={(event) => onChange(event.currentTarget.innerHTML)}
    />
  );
}

export function AdminPage() {
  const { user } = useAuth();
  const location = useLocation();
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const platform = PLATFORM_ROLES.has(user?.role ?? "");
  const readOnly = READ_ONLY_ROLES.has(user?.role ?? "");
  const tabs = useMemo(() => [
    "users", "rbac", "alerts",
    ...(platform ? ["trials", "companies", "billing"] : []),
  ], [platform]);
  const initialTab = location.pathname.includes("/rbac") ? "rbac"
    : location.pathname.includes("/alerts") ? "alerts"
      : location.pathname.includes("/trials") ? "trials"
        : location.pathname.includes("/companies") ? "companies"
          : location.pathname.includes("/billing") ? "billing"
            : "users";
  const [tab, setTab] = useState(tabs.includes(initialTab) ? initialTab : tabs[0]);

  const usersQ = useQuery({ queryKey: ["admin", "users"], queryFn: api.adminUsers, retry: 1 });
  const policiesQ = useQuery({ queryKey: ["rbac", "policies"], queryFn: () => api.rbacPolicies(), retry: 1 });
  const rolesQ = useQuery({ queryKey: ["rbac", "roles"], queryFn: api.roles, retry: 1 });
  const reportsQ = useQuery({ queryKey: ["reports"], queryFn: api.reports, retry: 1 });
  const emailAlertsQ = useQuery({ queryKey: ["alerts", "email"], queryFn: api.emailAlerts, retry: 1 });
  const reportAlertsQ = useQuery({ queryKey: ["alerts", "reports"], queryFn: api.reportAlerts, retry: 1 });
  const companiesQ = useQuery({ queryKey: ["admin", "companies"], queryFn: api.companies, enabled: platform, retry: 1 });
  const billingQ = useQuery({ queryKey: ["admin", "billing"], queryFn: api.billing, enabled: platform, retry: 1 });

  const [userDraft, setUserDraft] = useState({ displayName: "", email: "", role: "SALES", password: "axiom-demo" });
  const [emailDraft, setEmailDraft] = useState({ name: "", subject: "", bodyHtml: "<p>Hello,</p>", to: "", cc: "", bcc: "", attachmentOptional: true });
  const [reportDraft, setReportDraft] = useState({ name: "", subject: "", bodyHtml: "<p>The requested report is attached.</p>", to: "", cc: "", bcc: "", formats: "PDF", reportDefinitionId: "" });

  const createUser = useMutation({
    mutationFn: () => api.createAdminUser(userDraft),
    onSuccess: () => {
      toasts.push("info", "User created", "The tenant user can now sign in.");
      setUserDraft({ displayName: "", email: "", role: "SALES", password: "axiom-demo" });
      void queryClient.invalidateQueries({ queryKey: ["admin", "users"] });
    },
    onError: (error) => toasts.push("error", "User creation failed", error instanceof Error ? error.message : "Save failed."),
  });
  const activeUser = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) => api.setAdminUserActive(id, active),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["admin", "users"] }),
    onError: (error) => toasts.push("error", "User update failed", error instanceof Error ? error.message : "Update failed."),
  });
  const extendTrial = useMutation({
    mutationFn: (tenantId: string) => api.extendTrial(tenantId, { days: 7, months: 0, note: "Extended from super-admin trial screen" }),
    onSuccess: () => { toasts.push("info", "Trial extended", "Seven days were added to the trial."); void queryClient.invalidateQueries({ queryKey: ["admin", "companies"] }); },
    onError: (error) => toasts.push("error", "Trial extension failed", error instanceof Error ? error.message : "Update failed."),
  });
  const companyStatus = useMutation({
    mutationFn: ({ tenantId, status }: { tenantId: string; status: string }) => api.setCompanyStatus(tenantId, { status, reason: status === "PAST_DUE" ? "Payment due" : "Updated by super-admin" }),
    onSuccess: () => { toasts.push("info", "Company status updated", "Company lifecycle status is now enforced."); void queryClient.invalidateQueries({ queryKey: ["admin", "companies"] }); },
    onError: (error) => toasts.push("error", "Company update failed", error instanceof Error ? error.message : "Update failed."),
  });
  const createEmail = useMutation({
    mutationFn: () => api.createEmailAlert({ ...emailDraft, to: csv(emailDraft.to), cc: csv(emailDraft.cc), bcc: csv(emailDraft.bcc) }),
    onSuccess: () => { toasts.push("info", "Email alert saved", "Configuration is ready for dispatch."); void queryClient.invalidateQueries({ queryKey: ["alerts", "email"] }); },
    onError: (error) => toasts.push("error", "Email alert rejected", error instanceof Error ? error.message : "Save failed."),
  });
  const createReportAlert = useMutation({
    mutationFn: () => api.createReportAlert({ ...reportDraft, to: csv(reportDraft.to), cc: csv(reportDraft.cc), bcc: csv(reportDraft.bcc), formats: csv(reportDraft.formats), reportDefinitionId: reportDraft.reportDefinitionId || undefined }),
    onSuccess: () => { toasts.push("info", "Report alert saved", "Configuration is ready for report attachment dispatch."); void queryClient.invalidateQueries({ queryKey: ["alerts", "reports"] }); },
    onError: (error) => toasts.push("error", "Report alert rejected", error instanceof Error ? error.message : "Save failed."),
  });
  const sendEmail = useMutation({
    mutationFn: (id: string) => api.sendEmailAlert(id),
    onSuccess: (result) => toasts.push("info", "Email alert queued", result.message),
    onError: (error) => toasts.push("error", "Dispatch failed", error instanceof Error ? error.message : "Queue failed."),
  });
  const sendReport = useMutation({
    mutationFn: (id: string) => api.sendReportAlert(id),
    onSuccess: (result) => toasts.push("info", "Report alert queued", result.message),
    onError: (error) => toasts.push("error", "Report dispatch failed", error instanceof Error ? error.message : "Queue failed."),
  });

  if (isUnreachable(usersQ.error)) return <ApiUnreachable onRetry={() => void usersQ.refetch()} retrying={usersQ.isFetching} />;

  return <>
    <div className="page-head">
      <div><span className="eyebrow">Governance cockpit</span><h1>Administration</h1><p>RBAC-governed administration for users, trials, companies, billing and alerts.</p></div>
      {readOnly && <span className="count">Read-only audit mode</span>}
    </div>
    <div className="admin-tabs" role="tablist" aria-label="Administration modules">
      {tabs.map((item) => <button key={item} className={tab === item ? "active" : ""} onClick={() => setTab(item)}>{item.replace(/-/g, " ")}</button>)}
    </div>

    {tab === "users" && <DataViewFrame title="User management">
      {!readOnly && <div className="admin-form">
        <input value={userDraft.displayName} onChange={(event) => setUserDraft((v) => ({ ...v, displayName: event.target.value }))} placeholder="Display name" />
        <input value={userDraft.email} onChange={(event) => setUserDraft((v) => ({ ...v, email: event.target.value }))} placeholder="Email" />
        <select value={userDraft.role} onChange={(event) => setUserDraft((v) => ({ ...v, role: event.target.value }))}>{TENANT_ROLES.map((role) => <option key={role} value={role}>{role}</option>)}</select>
        <input value={userDraft.password} onChange={(event) => setUserDraft((v) => ({ ...v, password: event.target.value }))} placeholder="Initial password" />
        <button className="btn btn-primary btn-sm" disabled={createUser.isPending} onClick={() => createUser.mutate()}>Create user</button>
      </div>}
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Name</th><th>Email</th><th>Tenant</th><th>Role</th><th>Status</th>{!readOnly && <th className="table-action">Action</th>}</tr></thead>
        <tbody>{usersQ.data?.map((row) => <tr key={row.id}><td>{row.displayName}</td><td>{row.email}</td><td>{row.tenantName}</td><td>{row.role}</td><td>{row.active ? "Active" : "Inactive"}</td>{!readOnly && <td className="table-action">{!row.platformUser && <button className="link-btn" onClick={() => activeUser.mutate({ id: row.id, active: !row.active })}>{row.active ? "Inactivate" : "Activate"}</button>}</td>}</tr>)}</tbody>
      </table></div>
    </DataViewFrame>}

    {tab === "rbac" && <DataViewFrame title="RBAC policies">
      <div className="role-card-grid">{rolesQ.data?.map((role) => <article className="role-card" key={role.code}><strong>{role.code}</strong><span>{role.scope}</span><p>{role.description}</p></article>)}</div>
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Screen</th><th>Module</th><th>Route</th><th>Read</th><th>Write</th><th>Export</th><th>Admin</th></tr></thead>
        <tbody>{policiesQ.data?.map((p) => <tr key={`${p.roleCode}-${p.screenCode}`}><td>{p.displayName}</td><td>{p.moduleCode}</td><td>{p.route}</td><td>{p.canRead ? "Yes" : "No"}</td><td>{p.canWrite ? "Yes" : "No"}</td><td>{p.canExport ? "Yes" : "No"}</td><td>{p.canAdmin ? "Yes" : "No"}</td></tr>)}</tbody>
      </table></div>
    </DataViewFrame>}

    {tab === "alerts" && <DataViewFrame title="Email and report alerts">
      {!readOnly && <div className="alert-config-grid">
        <section className="config-card"><h2>Email alert configuration</h2>
          <input placeholder="Name" value={emailDraft.name} onChange={(event) => setEmailDraft((v) => ({ ...v, name: event.target.value }))} />
          <input placeholder="Subject" value={emailDraft.subject} onChange={(event) => setEmailDraft((v) => ({ ...v, subject: event.target.value }))} />
          <HtmlEditor value={emailDraft.bodyHtml} onChange={(bodyHtml) => setEmailDraft((v) => ({ ...v, bodyHtml }))} />
          <input placeholder="To emails, comma separated" value={emailDraft.to} onChange={(event) => setEmailDraft((v) => ({ ...v, to: event.target.value }))} />
          <input placeholder="CC emails, comma separated" value={emailDraft.cc} onChange={(event) => setEmailDraft((v) => ({ ...v, cc: event.target.value }))} />
          <input placeholder="BCC emails, comma separated" value={emailDraft.bcc} onChange={(event) => setEmailDraft((v) => ({ ...v, bcc: event.target.value }))} />
          <label className="check-line"><input type="checkbox" checked={emailDraft.attachmentOptional} onChange={(event) => setEmailDraft((v) => ({ ...v, attachmentOptional: event.target.checked }))} /> Attachment optional</label>
          <button className="btn btn-primary btn-sm" disabled={createEmail.isPending} onClick={() => createEmail.mutate()}>Save email alert</button>
        </section>
        <section className="config-card"><h2>Report alert configuration</h2>
          <input placeholder="Name" value={reportDraft.name} onChange={(event) => setReportDraft((v) => ({ ...v, name: event.target.value }))} />
          <input placeholder="Subject" value={reportDraft.subject} onChange={(event) => setReportDraft((v) => ({ ...v, subject: event.target.value }))} />
          <select value={reportDraft.reportDefinitionId} onChange={(event) => setReportDraft((v) => ({ ...v, reportDefinitionId: event.target.value }))}>
            <option value="">Tenant Summary</option>
            {reportsQ.data?.map((report: ReportDefinition) => <option key={report.id} value={report.id}>{report.label}</option>)}
          </select>
          <HtmlEditor value={reportDraft.bodyHtml} onChange={(bodyHtml) => setReportDraft((v) => ({ ...v, bodyHtml }))} />
          <input placeholder="To emails, comma separated" value={reportDraft.to} onChange={(event) => setReportDraft((v) => ({ ...v, to: event.target.value }))} />
          <input placeholder="CC emails, comma separated" value={reportDraft.cc} onChange={(event) => setReportDraft((v) => ({ ...v, cc: event.target.value }))} />
          <input placeholder="BCC emails, comma separated" value={reportDraft.bcc} onChange={(event) => setReportDraft((v) => ({ ...v, bcc: event.target.value }))} />
          <input placeholder="Formats: PDF, XLSX, DOCX" value={reportDraft.formats} onChange={(event) => setReportDraft((v) => ({ ...v, formats: event.target.value }))} />
          <button className="btn btn-primary btn-sm" disabled={createReportAlert.isPending} onClick={() => createReportAlert.mutate()}>Save report alert</button>
        </section>
      </div>}
      <div className="alert-config-grid">
        <AlertTable title="Email alerts" rows={emailAlertsQ.data?.map((row) => ({ id: row.id, name: row.name, subject: row.subject, detail: row.to.join(", "), action: () => sendEmail.mutate(row.id) })) ?? []} readOnly={readOnly} />
        <AlertTable title="Report alerts" rows={reportAlertsQ.data?.map((row) => ({ id: row.id, name: row.name, subject: row.subject, detail: `${row.reportLabel} · ${row.formats.join(", ")}`, action: () => sendReport.mutate(row.id) })) ?? []} readOnly={readOnly} />
      </div>
    </DataViewFrame>}

    {tab === "trials" && platform && <DataViewFrame title="Trial accounts">
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Company</th><th>Status</th><th>Trial end</th><th>Extensions</th>{!readOnly && <th className="table-action">Action</th>}</tr></thead>
        <tbody>{companiesQ.data?.map((row) => <tr key={row.tenantId}><td>{row.tenantName}</td><td>{row.accountStatus}</td><td>{formatDate(row.trialEndsAt)}</td><td>{row.trialExtensionCount} / {row.maxTrialExtensionDays} days max</td>{!readOnly && <td className="table-action"><button className="link-btn" onClick={() => extendTrial.mutate(row.tenantId)}>Extend 7d</button></td>}</tr>)}</tbody>
      </table></div>
    </DataViewFrame>}

    {tab === "companies" && platform && <DataViewFrame title="Company setup accounts">
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Company</th><th>Workspace</th><th>Status</th><th>Reason</th>{!readOnly && <th className="table-action">Action</th>}</tr></thead>
        <tbody>{companiesQ.data?.map((row) => <tr key={row.tenantId}><td>{row.legalName}</td><td>{row.tenantSlug}</td><td>{row.accountStatus}</td><td>{row.inactiveReason ?? "-"}</td>{!readOnly && <td className="table-action company-actions"><button className="link-btn" onClick={() => companyStatus.mutate({ tenantId: row.tenantId, status: "ACTIVE" })}>Active</button><button className="link-btn danger-link" onClick={() => companyStatus.mutate({ tenantId: row.tenantId, status: "PAST_DUE" })}>Past due</button></td>}</tr>)}</tbody>
      </table></div>
    </DataViewFrame>}

    {tab === "billing" && platform && <DataViewFrame title="Billing">
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Company</th><th>Plan</th><th>Payment</th><th>Billing email</th><th>Invoice</th><th>Amount</th><th>Due</th></tr></thead>
        <tbody>{billingQ.data?.map((row) => <tr key={row.tenantId}><td>{row.tenantName}</td><td>{row.planCode}</td><td>{row.paymentStatus}</td><td>{row.billingEmail}</td><td>{row.invoiceNumber ?? "-"}</td><td>{row.amount == null ? "-" : formatMoney(row.amount)}</td><td>{formatDate(row.dueAt)}</td></tr>)}</tbody>
      </table></div>
    </DataViewFrame>}
  </>;
}

function AlertTable({ title, rows, readOnly }: { title: string; rows: Array<{ id: string; name: string; subject: string; detail: string; action: () => void }>; readOnly: boolean }) {
  return <section className="config-card"><h2>{title}</h2><div className="table-wrap"><table className="data-table"><thead><tr><th>Name</th><th>Subject</th><th>Recipients / report</th>{!readOnly && <th className="table-action">Action</th>}</tr></thead>
    <tbody>{rows.map((row) => <tr key={row.id}><td>{row.name}</td><td>{row.subject}</td><td>{row.detail}</td>{!readOnly && <td className="table-action"><button className="link-btn" onClick={row.action}>Queue</button></td>}</tr>)}
    {rows.length === 0 && <tr><td colSpan={readOnly ? 3 : 4} className="empty-note">No alerts configured.</td></tr>}</tbody>
  </table></div></section>;
}
