import { Fragment, useMemo, useState, type InputHTMLAttributes, type SelectHTMLAttributes } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocation } from "react-router-dom";
import { api, isUnreachable, type ReportDefinition } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar } from "../components/DataGridToolbar";
import { DataViewFrame } from "../components/DataViewFrame";
import { InfoTag } from "../components/InfoTag";
import { useToasts } from "../components/Toasts";
import { formatDate, formatMoney } from "../lib/format";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, sortByGroups, type GroupColumn } from "../lib/gridGrouping";
import { useLocalStorageState } from "../lib/usePersistedGridState";

const PLATFORM_ROLES = new Set(["SUPER_ADMIN", "SUPER_AUDIT"]);
const READ_ONLY_ROLES = new Set(["SUPER_AUDIT", "AUDITOR"]);
const TENANT_ROLES = ["TENANT_ADMIN", "SALES_MANAGER", "SALES", "MARKETING", "SERVICE", "OPERATIONS", "FINANCE", "DATA_STEWARD", "AUDITOR", "INTEGRATION"];
const ADMIN_GROUP_COLUMNS: Record<string, GroupColumn<any>[]> = {
  users: [
    { key: "name", label: "Name", value: (row) => row.displayName },
    { key: "email", label: "Email", value: (row) => row.email },
    { key: "role", label: "Role", value: (row) => row.role },
    { key: "tenant", label: "Tenant", value: (row) => row.tenantName },
    { key: "status", label: "Status", value: (row) => row.active ? "Active" : "Inactive" },
  ],
  rbac: [
    { key: "screen", label: "Screen", value: (row) => row.displayName },
    { key: "module", label: "Module", value: (row) => row.moduleCode },
    { key: "route", label: "Route", value: (row) => row.route },
    { key: "role", label: "Role", value: (row) => row.roleCode },
    { key: "scope", label: "Scope", value: (row) => row.scope },
  ],
  alerts: [
    { key: "type", label: "Alert type", value: (row) => row.type },
    { key: "name", label: "Name", value: (row) => row.name },
    { key: "subject", label: "Subject", value: (row) => row.subject },
    { key: "status", label: "Status", value: (row) => row.active },
  ],
  trials: [
    { key: "tenant", label: "Company", value: (row) => row.tenantName },
    { key: "status", label: "Account status", value: (row) => row.accountStatus },
    { key: "trialEnd", label: "Trial end", value: (row) => row.trialEndsAt },
  ],
  companies: [
    { key: "company", label: "Company", value: (row) => row.legalName },
    { key: "tenant", label: "Tenant", value: (row) => row.tenantName },
    { key: "status", label: "Account status", value: (row) => row.accountStatus },
    { key: "workspace", label: "Workspace", value: (row) => row.tenantSlug },
  ],
  billing: [
    { key: "company", label: "Company", value: (row) => row.tenantName },
    { key: "payment", label: "Payment status", value: (row) => row.paymentStatus },
    { key: "plan", label: "Plan", value: (row) => row.planCode },
    { key: "billingEmail", label: "Billing email", value: (row) => row.billingEmail },
  ],
};

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

function InfoInput({ info, ...props }: InputHTMLAttributes<HTMLInputElement> & { info: string }) {
  const label = props.placeholder ?? props["aria-label"] ?? "Field";
  return (
    <label className="input-info-wrap">
      <InfoTag text={info} label={`${label} help`} />
      <input title={info} {...props} />
    </label>
  );
}

function InfoSelect({ info, children, ...props }: SelectHTMLAttributes<HTMLSelectElement> & { info: string }) {
  const label = props["aria-label"] ?? "Selection";
  return (
    <label className="input-info-wrap">
      <InfoTag text={info} label={`${label} help`} />
      <select title={info} {...props}>{children}</select>
    </label>
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
  const [groupedTabs, setGroupedTabs] = useLocalStorageState<Record<string, string[]>>("axiom.grid.admin.grouped-tabs", {}, sanitizeGroupedTabs);
  const [columnFiltersByTab, setColumnFiltersByTab] = useLocalStorageState<Record<string, Record<string, string>>>("axiom.grid.admin.column-filters", {}, sanitizeColumnFiltersByTab);

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
  const activeGroupKeys = groupedTabs[tab] ?? [];
  const activeGroupColumns = selectedGroupColumns(ADMIN_GROUP_COLUMNS[tab] ?? [], activeGroupKeys);
  const users = sortByGroups(filterRowsByColumns(usersQ.data ?? [], ADMIN_GROUP_COLUMNS.users, columnFiltersByTab.users ?? {}), selectedGroupColumns(ADMIN_GROUP_COLUMNS.users, groupedTabs.users ?? []), (row) => row.displayName);
  const policies = sortByGroups(filterRowsByColumns(policiesQ.data ?? [], ADMIN_GROUP_COLUMNS.rbac, columnFiltersByTab.rbac ?? {}), selectedGroupColumns(ADMIN_GROUP_COLUMNS.rbac, groupedTabs.rbac ?? []), (row) => row.displayName);
  const companies = sortByGroups(filterRowsByColumns(companiesQ.data ?? [], ADMIN_GROUP_COLUMNS[tab] ?? ADMIN_GROUP_COLUMNS.companies, columnFiltersByTab[tab] ?? {}), selectedGroupColumns(ADMIN_GROUP_COLUMNS[tab] ?? ADMIN_GROUP_COLUMNS.companies, groupedTabs[tab] ?? []), (row) => row.tenantName);
  const billing = sortByGroups(filterRowsByColumns(billingQ.data ?? [], ADMIN_GROUP_COLUMNS.billing, columnFiltersByTab.billing ?? {}), selectedGroupColumns(ADMIN_GROUP_COLUMNS.billing, groupedTabs.billing ?? []), (row) => row.tenantName);
  const alertRows = [
    ...(emailAlertsQ.data ?? []).map((row) => ({ type: "Email", name: row.name, subject: row.subject, recipientsOrReport: row.to.join(", "), active: row.active ? "Active" : "Inactive" })),
    ...(reportAlertsQ.data ?? []).map((row) => ({ type: "Report", name: row.name, subject: row.subject, recipientsOrReport: `${row.reportLabel} · ${row.formats.join(", ")}`, active: row.active ? "Active" : "Inactive" })),
  ];
  const filteredAlertRows = filterRowsByColumns(alertRows, ADMIN_GROUP_COLUMNS.alerts, columnFiltersByTab.alerts ?? {});
  const visibleEmailAlertKeys = new Set(filteredAlertRows.filter((row) => row.type === "Email").map((row) => `${row.name}|${row.subject}`));
  const visibleReportAlertKeys = new Set(filteredAlertRows.filter((row) => row.type === "Report").map((row) => `${row.name}|${row.subject}`));

  function setTabGroups(key: string, next: string[]) {
    setGroupedTabs((value) => ({ ...value, [key]: next }));
  }

  function setTabFilters(key: string, next: Record<string, string>) {
    setColumnFiltersByTab((value) => ({ ...value, [key]: next }));
  }

  function toggleGroup(key = tab, fallback: string) {
    setGroupedTabs((value) => ({ ...value, [key]: value[key]?.length ? [] : [fallback] }));
  }

  return <>
    <div className="page-head">
      <div><span className="eyebrow">Governance cockpit</span><h1>Administration</h1><p>RBAC-governed administration for users, trials, companies, billing and alerts.</p></div>
      {readOnly && <span className="count">Read-only audit mode</span>}
    </div>
    <div className="admin-tabs" role="tablist" aria-label="Administration modules">
      {tabs.map((item) => <button key={item} className={tab === item ? "active" : ""} onClick={() => setTab(item)}>{item.replace(/-/g, " ")}</button>)}
    </div>

    {tab === "users" && <DataViewFrame
      title="User management"
      actions={<DataGridToolbar
        gridName="User management"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Role"
        onToggleGroup={() => toggleGroup("users", "role")}
        groupColumns={ADMIN_GROUP_COLUMNS.users.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupedTabs.users ?? []}
        onGroupColumnsChange={(next) => setTabGroups("users", next)}
        filterColumns={ADMIN_GROUP_COLUMNS.users.map(({ key, label }) => ({ key, label }))}
        columnFilters={columnFiltersByTab.users ?? {}}
        onColumnFiltersChange={(next) => setTabFilters("users", next)}
        auditEntityType="APP_USER"
        exportFilename="admin-users"
        exportRows={users.map((row) => ({
          name: row.displayName,
          email: row.email,
          tenant: row.tenantName,
          role: row.role,
          status: row.active ? "Active" : "Inactive",
          platformUser: row.platformUser ? "Yes" : "No",
        }))}
      />}
    >
      {!readOnly && <div className="admin-form" aria-label="Create user form">
        <InfoInput info="The person's name as it should appear in Axiom." value={userDraft.displayName} onChange={(event) => setUserDraft((v) => ({ ...v, displayName: event.target.value }))} placeholder="Display name" />
        <InfoInput info="The work email this user will use to sign in." value={userDraft.email} onChange={(event) => setUserDraft((v) => ({ ...v, email: event.target.value }))} placeholder="Email" />
        <InfoSelect info="The role decides which screens and actions this user can access." aria-label="Role" value={userDraft.role} onChange={(event) => setUserDraft((v) => ({ ...v, role: event.target.value }))}>{TENANT_ROLES.map((role) => <option key={role} value={role}>{role}</option>)}</InfoSelect>
        <InfoInput info="A temporary password for the user's first sign-in." value={userDraft.password} onChange={(event) => setUserDraft((v) => ({ ...v, password: event.target.value }))} placeholder="Initial password" />
        <button className="btn btn-primary btn-sm" disabled={createUser.isPending} onClick={() => createUser.mutate()}>Create user</button>
      </div>}
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Name</th><th>Email</th><th>Tenant</th><th>Role</th><th>Status</th>{!readOnly && <th className="table-action">Action</th>}</tr></thead>
        <tbody>{users.map((row, index, all) => <Fragment key={row.id}>
          {selectedGroupColumns(ADMIN_GROUP_COLUMNS.users, groupedTabs.users ?? []).length > 0 && groupChangedBySelection(index, all, selectedGroupColumns(ADMIN_GROUP_COLUMNS.users, groupedTabs.users ?? [])) && <tr className="group-row"><th colSpan={readOnly ? 5 : 6}>{groupLabelFor(row, selectedGroupColumns(ADMIN_GROUP_COLUMNS.users, groupedTabs.users ?? []))}</th></tr>}
          <tr><td>{row.displayName}</td><td>{row.email}</td><td>{row.tenantName}</td><td>{row.role}</td><td>{row.active ? "Active" : "Inactive"}</td>{!readOnly && <td className="table-action">{!row.platformUser && <button className="link-btn" onClick={() => activeUser.mutate({ id: row.id, active: !row.active })}>{row.active ? "Inactivate" : "Activate"}</button>}</td>}</tr>
        </Fragment>)}</tbody>
      </table></div>
    </DataViewFrame>}

    {tab === "rbac" && <DataViewFrame
      title="RBAC policies"
      actions={<DataGridToolbar
        gridName="RBAC policies"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Module"
        onToggleGroup={() => toggleGroup("rbac", "module")}
        groupColumns={ADMIN_GROUP_COLUMNS.rbac.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupedTabs.rbac ?? []}
        onGroupColumnsChange={(next) => setTabGroups("rbac", next)}
        filterColumns={ADMIN_GROUP_COLUMNS.rbac.map(({ key, label }) => ({ key, label }))}
        columnFilters={columnFiltersByTab.rbac ?? {}}
        onColumnFiltersChange={(next) => setTabFilters("rbac", next)}
        auditEntityType="SCREEN_POLICY"
        exportFilename="rbac-policies"
        exportRows={policies.map((row) => ({
          role: row.roleCode,
          screen: row.displayName,
          module: row.moduleCode,
          route: row.route,
          read: row.canRead ? "Yes" : "No",
          write: row.canWrite ? "Yes" : "No",
          export: row.canExport ? "Yes" : "No",
          admin: row.canAdmin ? "Yes" : "No",
          scope: row.scope,
        }))}
      />}
    >
      <div className="role-card-grid">{rolesQ.data?.map((role) => <article className="role-card" key={role.code}><strong>{role.code}</strong><span>{role.scope}</span><p>{role.description}</p></article>)}</div>
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Screen</th><th>Module</th><th>Route</th><th>Read</th><th>Write</th><th>Export</th><th>Admin</th></tr></thead>
        <tbody>{policies.map((p, index, all) => <Fragment key={`${p.roleCode}-${p.screenCode}`}>
          {selectedGroupColumns(ADMIN_GROUP_COLUMNS.rbac, groupedTabs.rbac ?? []).length > 0 && groupChangedBySelection(index, all, selectedGroupColumns(ADMIN_GROUP_COLUMNS.rbac, groupedTabs.rbac ?? [])) && <tr className="group-row"><th colSpan={7}>{groupLabelFor(p, selectedGroupColumns(ADMIN_GROUP_COLUMNS.rbac, groupedTabs.rbac ?? []))}</th></tr>}
          <tr><td>{p.displayName}</td><td>{p.moduleCode}</td><td>{p.route}</td><td>{p.canRead ? "Yes" : "No"}</td><td>{p.canWrite ? "Yes" : "No"}</td><td>{p.canExport ? "Yes" : "No"}</td><td>{p.canAdmin ? "Yes" : "No"}</td></tr>
        </Fragment>)}</tbody>
      </table></div>
    </DataViewFrame>}

    {tab === "alerts" && <DataViewFrame
      title="Email and report alerts"
      actions={<DataGridToolbar
        gridName="Email and report alerts"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Alert type"
        onToggleGroup={() => toggleGroup("alerts", "type")}
        groupColumns={ADMIN_GROUP_COLUMNS.alerts.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupedTabs.alerts ?? []}
        onGroupColumnsChange={(next) => setTabGroups("alerts", next)}
        filterColumns={ADMIN_GROUP_COLUMNS.alerts.map(({ key, label }) => ({ key, label }))}
        columnFilters={columnFiltersByTab.alerts ?? {}}
        onColumnFiltersChange={(next) => setTabFilters("alerts", next)}
        auditEntityType="ALERT_CONFIGURATION"
        exportFilename="alert-configurations"
        exportRows={filteredAlertRows}
        note="Email and report alert configuration"
      />}
    >
      {!readOnly && <div className="alert-config-grid">
        <section className="config-card"><h2 className="form-title-with-info"><span>Email alert configuration</span><InfoTag text="Build a reusable email message and choose who receives it. Attachments are optional." label="Email alert form help" /></h2>
          <InfoInput info="A friendly name so admins can recognize this alert later." placeholder="Name" value={emailDraft.name} onChange={(event) => setEmailDraft((v) => ({ ...v, name: event.target.value }))} />
          <InfoInput info="The subject line people will see in their inbox." placeholder="Subject" value={emailDraft.subject} onChange={(event) => setEmailDraft((v) => ({ ...v, subject: event.target.value }))} />
          <div className="form-title-with-info rich-editor-label"><span>Mail body</span><InfoTag text="Write the message content. Formatting is supported, just like a simple email editor." label="Mail body help" /></div>
          <HtmlEditor value={emailDraft.bodyHtml} onChange={(bodyHtml) => setEmailDraft((v) => ({ ...v, bodyHtml }))} />
          <InfoInput info="Main recipients. Use commas when adding more than one email." placeholder="To emails, comma separated" value={emailDraft.to} onChange={(event) => setEmailDraft((v) => ({ ...v, to: event.target.value }))} />
          <InfoInput info="People copied for awareness. Use commas for multiple emails." placeholder="CC emails, comma separated" value={emailDraft.cc} onChange={(event) => setEmailDraft((v) => ({ ...v, cc: event.target.value }))} />
          <InfoInput info="Hidden recipients who receive a private copy." placeholder="BCC emails, comma separated" value={emailDraft.bcc} onChange={(event) => setEmailDraft((v) => ({ ...v, bcc: event.target.value }))} />
          <label className="check-line"><input type="checkbox" checked={emailDraft.attachmentOptional} onChange={(event) => setEmailDraft((v) => ({ ...v, attachmentOptional: event.target.checked }))} /> Attachment optional</label>
          <button className="btn btn-primary btn-sm" disabled={createEmail.isPending} onClick={() => createEmail.mutate()}>Save email alert</button>
        </section>
        <section className="config-card"><h2 className="form-title-with-info"><span>Report alert configuration</span><InfoTag text="Choose a report, write the email, and Axiom will attach the selected file formats." label="Report alert form help" /></h2>
          <InfoInput info="A friendly name so admins can recognize this report alert later." placeholder="Name" value={reportDraft.name} onChange={(event) => setReportDraft((v) => ({ ...v, name: event.target.value }))} />
          <InfoInput info="The email subject line people will see when the report arrives." placeholder="Subject" value={reportDraft.subject} onChange={(event) => setReportDraft((v) => ({ ...v, subject: event.target.value }))} />
          <InfoSelect info="Pick the report that should be attached to the email." aria-label="Report definition" value={reportDraft.reportDefinitionId} onChange={(event) => setReportDraft((v) => ({ ...v, reportDefinitionId: event.target.value }))}>
            <option value="">Tenant Summary</option>
            {reportsQ.data?.map((report: ReportDefinition) => <option key={report.id} value={report.id}>{report.label}</option>)}
          </InfoSelect>
          <div className="form-title-with-info rich-editor-label"><span>Mail body</span><InfoTag text="Write the message that explains the attached report." label="Report mail body help" /></div>
          <HtmlEditor value={reportDraft.bodyHtml} onChange={(bodyHtml) => setReportDraft((v) => ({ ...v, bodyHtml }))} />
          <InfoInput info="Main recipients. Use commas when adding more than one email." placeholder="To emails, comma separated" value={reportDraft.to} onChange={(event) => setReportDraft((v) => ({ ...v, to: event.target.value }))} />
          <InfoInput info="People copied for awareness. Use commas for multiple emails." placeholder="CC emails, comma separated" value={reportDraft.cc} onChange={(event) => setReportDraft((v) => ({ ...v, cc: event.target.value }))} />
          <InfoInput info="Hidden recipients who receive a private copy." placeholder="BCC emails, comma separated" value={reportDraft.bcc} onChange={(event) => setReportDraft((v) => ({ ...v, bcc: event.target.value }))} />
          <InfoInput info="Choose output formats such as PDF, XLSX, or DOCX, separated by commas." placeholder="Formats: PDF, XLSX, DOCX" value={reportDraft.formats} onChange={(event) => setReportDraft((v) => ({ ...v, formats: event.target.value }))} />
          <button className="btn btn-primary btn-sm" disabled={createReportAlert.isPending} onClick={() => createReportAlert.mutate()}>Save report alert</button>
        </section>
      </div>}
      <div className="alert-config-grid">
        <AlertTable title="Email alerts" rows={emailAlertsQ.data?.filter((row) => visibleEmailAlertKeys.has(`${row.name}|${row.subject}`)).map((row) => ({ id: row.id, name: row.name, subject: row.subject, detail: row.to.join(", "), action: () => sendEmail.mutate(row.id) })) ?? []} readOnly={readOnly} />
        <AlertTable title="Report alerts" rows={reportAlertsQ.data?.filter((row) => visibleReportAlertKeys.has(`${row.name}|${row.subject}`)).map((row) => ({ id: row.id, name: row.name, subject: row.subject, detail: `${row.reportLabel} · ${row.formats.join(", ")}`, action: () => sendReport.mutate(row.id) })) ?? []} readOnly={readOnly} />
      </div>
    </DataViewFrame>}

    {tab === "trials" && platform && <DataViewFrame
      title="Trial accounts"
      actions={<DataGridToolbar
        gridName="Trial accounts"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Account status"
        onToggleGroup={() => toggleGroup("trials", "status")}
        groupColumns={ADMIN_GROUP_COLUMNS.trials.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupedTabs.trials ?? []}
        onGroupColumnsChange={(next) => setTabGroups("trials", next)}
        filterColumns={ADMIN_GROUP_COLUMNS.trials.map(({ key, label }) => ({ key, label }))}
        columnFilters={columnFiltersByTab.trials ?? {}}
        onColumnFiltersChange={(next) => setTabFilters("trials", next)}
        auditEntityType="TRIAL_ACCOUNT"
        exportFilename="trial-accounts"
        exportRows={companies.map((row) => ({
          company: row.tenantName,
          status: row.accountStatus,
          trialStart: row.trialStartAt ?? "",
          trialEnd: row.trialEndsAt ?? "",
          extensions: row.trialExtensionCount,
          maxExtensionDays: row.maxTrialExtensionDays,
        }))}
      />}
    >
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Company</th><th>Status</th><th>Trial end</th><th>Extensions</th>{!readOnly && <th className="table-action">Action</th>}</tr></thead>
        <tbody>{companies.map((row, index, all) => <Fragment key={row.tenantId}>
          {selectedGroupColumns(ADMIN_GROUP_COLUMNS.trials, groupedTabs.trials ?? []).length > 0 && groupChangedBySelection(index, all, selectedGroupColumns(ADMIN_GROUP_COLUMNS.trials, groupedTabs.trials ?? [])) && <tr className="group-row"><th colSpan={readOnly ? 4 : 5}>{groupLabelFor(row, selectedGroupColumns(ADMIN_GROUP_COLUMNS.trials, groupedTabs.trials ?? []))}</th></tr>}
          <tr><td>{row.tenantName}</td><td>{row.accountStatus}</td><td>{formatDate(row.trialEndsAt)}</td><td>{row.trialExtensionCount} / {row.maxTrialExtensionDays} days max</td>{!readOnly && <td className="table-action"><button className="link-btn" onClick={() => extendTrial.mutate(row.tenantId)}>Extend 7d</button></td>}</tr>
        </Fragment>)}</tbody>
      </table></div>
    </DataViewFrame>}

    {tab === "companies" && platform && <DataViewFrame
      title="Company setup accounts"
      actions={<DataGridToolbar
        gridName="Company setup accounts"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Account status"
        onToggleGroup={() => toggleGroup("companies", "status")}
        groupColumns={ADMIN_GROUP_COLUMNS.companies.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupedTabs.companies ?? []}
        onGroupColumnsChange={(next) => setTabGroups("companies", next)}
        filterColumns={ADMIN_GROUP_COLUMNS.companies.map(({ key, label }) => ({ key, label }))}
        columnFilters={columnFiltersByTab.companies ?? {}}
        onColumnFiltersChange={(next) => setTabFilters("companies", next)}
        auditEntityType="COMPANY_ACCOUNT"
        exportFilename="company-setup-accounts"
        exportRows={companies.map((row) => ({
          company: row.legalName,
          workspace: row.tenantSlug,
          tenant: row.tenantName,
          status: row.accountStatus,
          inactiveReason: row.inactiveReason ?? "",
          inactiveAt: row.inactiveAt ?? "",
        }))}
      />}
    >
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Company</th><th>Workspace</th><th>Status</th><th>Reason</th>{!readOnly && <th className="table-action">Action</th>}</tr></thead>
        <tbody>{companies.map((row, index, all) => <Fragment key={row.tenantId}>
          {selectedGroupColumns(ADMIN_GROUP_COLUMNS.companies, groupedTabs.companies ?? []).length > 0 && groupChangedBySelection(index, all, selectedGroupColumns(ADMIN_GROUP_COLUMNS.companies, groupedTabs.companies ?? [])) && <tr className="group-row"><th colSpan={readOnly ? 4 : 5}>{groupLabelFor(row, selectedGroupColumns(ADMIN_GROUP_COLUMNS.companies, groupedTabs.companies ?? []))}</th></tr>}
          <tr><td>{row.legalName}</td><td>{row.tenantSlug}</td><td>{row.accountStatus}</td><td>{row.inactiveReason ?? "-"}</td>{!readOnly && <td className="table-action company-actions"><button className="link-btn" onClick={() => companyStatus.mutate({ tenantId: row.tenantId, status: "ACTIVE" })}>Active</button><button className="link-btn danger-link" onClick={() => companyStatus.mutate({ tenantId: row.tenantId, status: "PAST_DUE" })}>Past due</button></td>}</tr>
        </Fragment>)}</tbody>
      </table></div>
    </DataViewFrame>}

    {tab === "billing" && platform && <DataViewFrame
      title="Billing"
      actions={<DataGridToolbar
        gridName="Billing"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Payment status"
        onToggleGroup={() => toggleGroup("billing", "payment")}
        groupColumns={ADMIN_GROUP_COLUMNS.billing.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupedTabs.billing ?? []}
        onGroupColumnsChange={(next) => setTabGroups("billing", next)}
        filterColumns={ADMIN_GROUP_COLUMNS.billing.map(({ key, label }) => ({ key, label }))}
        columnFilters={columnFiltersByTab.billing ?? {}}
        onColumnFiltersChange={(next) => setTabFilters("billing", next)}
        auditEntityType="BILLING_ACCOUNT"
        exportFilename="billing"
        exportRows={billing.map((row) => ({
          company: row.tenantName,
          plan: row.planCode,
          payment: row.paymentStatus,
          billingEmail: row.billingEmail,
          invoice: row.invoiceNumber ?? "",
          amount: row.amount ?? "",
          currency: row.currency ?? "",
          invoiceStatus: row.invoiceStatus ?? "",
          dueAt: row.dueAt ?? "",
        }))}
      />}
    >
      <div className="table-wrap"><table className="data-table"><thead><tr><th>Company</th><th>Plan</th><th>Payment</th><th>Billing email</th><th>Invoice</th><th>Amount</th><th>Due</th></tr></thead>
        <tbody>{billing.map((row, index, all) => <Fragment key={row.tenantId}>
          {selectedGroupColumns(ADMIN_GROUP_COLUMNS.billing, groupedTabs.billing ?? []).length > 0 && groupChangedBySelection(index, all, selectedGroupColumns(ADMIN_GROUP_COLUMNS.billing, groupedTabs.billing ?? [])) && <tr className="group-row"><th colSpan={7}>{groupLabelFor(row, selectedGroupColumns(ADMIN_GROUP_COLUMNS.billing, groupedTabs.billing ?? []))}</th></tr>}
          <tr><td>{row.tenantName}</td><td>{row.planCode}</td><td>{row.paymentStatus}</td><td>{row.billingEmail}</td><td>{row.invoiceNumber ?? "-"}</td><td>{row.amount == null ? "-" : formatMoney(row.amount)}</td><td>{formatDate(row.dueAt)}</td></tr>
        </Fragment>)}</tbody>
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

function groupChangedBySelection<T>(index: number, rows: T[], columns: GroupColumn<T>[]): boolean {
  return columns.length > 0 && (index === 0 || groupLabelFor(rows[index - 1], columns) !== groupLabelFor(rows[index], columns));
}

function sanitizeGroupedTabs(value: unknown): Record<string, string[]> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return Object.fromEntries(Object.entries(value)
    .filter((entry): entry is [string, unknown[]] => typeof entry[0] === "string" && Array.isArray(entry[1]))
    .map(([key, columns]) => [key, columns.filter((column): column is string => typeof column === "string")]));
}

function sanitizeColumnFiltersByTab(value: unknown): Record<string, Record<string, string>> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return Object.fromEntries(Object.entries(value)
    .filter((entry): entry is [string, Record<string, unknown>] => typeof entry[0] === "string" && !!entry[1] && typeof entry[1] === "object" && !Array.isArray(entry[1]))
    .map(([key, filters]) => [key, Object.fromEntries(Object.entries(filters)
      .filter((entry): entry is [string, string] => typeof entry[0] === "string" && typeof entry[1] === "string" && entry[1].trim().length > 0))]));
}
