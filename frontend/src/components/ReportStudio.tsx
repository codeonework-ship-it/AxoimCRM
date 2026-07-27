import { useEffect, useMemo, useState, type DragEvent, type CSSProperties } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  formatCell,
  formatLag,
  reportingApi,
  type CalculatedMeasure,
  type ConditionalRule,
  type DatasetField,
  type ReportRequest,
  type ReportResult,
  type SavedReport,
} from "../api/reporting";
import { useToasts } from "./Toasts";
import { useAuth } from "../auth/AuthContext";
import { useI18n } from "../i18n/I18nProvider";
import { InfoTag } from "./InfoTag";

type StudioTab = "BUILDER" | "DASHBOARDS" | "DELIVERY" | "OPERATIONS";
type DropZone = "columns" | "groups" | "measures" | "pivot";

const EMPTY_RULE: ConditionalRule = { field: "", operator: "GT", value: "0", foreground: "#F8FAFC", background: "#9A3412" };

export function ReportStudio() {
  const { t, tp } = useI18n();
  const [tab, setTab] = useState<StudioTab>("BUILDER");
  const { user } = useAuth();
  const mayOperate = ["SUPER_ADMIN", "TENANT_ADMIN", "DATA_STEWARD"].includes(user?.role ?? "");
  return <section className="report-studio panel" aria-label={t("ui.report.customReports", "Custom Reports")}>
    <header className="studio-head">
      <div><span className="eyebrow">{t("ui.report.customReports", "Custom Reports")}</span><h2>{tp("Build, Explain And Distribute")}</h2>
        <p>{tp("Compose governed reports and dashboards without bypassing tenant access, field security or performance limits.")}</p></div>
      <nav className="studio-tabs" aria-label="Custom Report Sections">
        {(["BUILDER", "DASHBOARDS", "DELIVERY", ...(mayOperate ? ["OPERATIONS" as const] : [])] as StudioTab[]).map(value =>
          <button key={value} className={`btn btn-sm ${tab === value ? "primary" : ""}`} onClick={() => setTab(value)}>
            {value === "BUILDER" ? "Report Builder" : value === "DASHBOARDS" ? "Dashboards" : value === "DELIVERY" ? "Share & Deliver" : "Certification"}
          </button>)}
      </nav>
    </header>
    {tab === "BUILDER" && <Builder />}
    {tab === "DASHBOARDS" && <DashboardDesigner />}
    {tab === "DELIVERY" && <DeliveryWorkspace />}
    {tab === "OPERATIONS" && mayOperate && <ReportingOperations />}
  </section>;
}

function ReportingOperations() {
  const toasts = useToasts();
  const client = useQueryClient();
  const certificationsQ = useQuery({ queryKey: ["analytics", "certifications"], queryFn: reportingApi.certifications });
  const recover = useMutation({
    mutationFn: () => reportingApi.rebuildAndReconcile("Operator-requested P0E15 recovery"),
    onSuccess: value => { void client.invalidateQueries({ queryKey: ["analytics"] }); toasts.push(value.status === "PASS" ? "info" : "error", "Projection recovery complete", value.verdict); },
    onError: error => toasts.push("error", "Projection recovery failed", message(error)),
  });
  const reconcile = useMutation({
    mutationFn: reportingApi.reconcileKpis,
    onSuccess: value => toasts.push(value.drifted === 0 ? "info" : "error", "KPI reconciliation complete", value.verdict),
    onError: error => toasts.push("error", "KPI reconciliation failed", message(error)),
  });
  const certify = useMutation({
    mutationFn: reportingApi.certifyProduction,
    onSuccess: value => { void client.invalidateQueries({ queryKey: ["analytics", "certifications"] }); toasts.push(value.status === "PASS" ? "info" : "error", `Certification: ${title(value.status)}`, value.verdict); },
    onError: error => toasts.push("error", "Certification failed", message(error)),
  });
  const latest = certificationsQ.data?.[0];
  return <div className="governance-workspace">
    <div className="governance-compose"><div><span className="eyebrow">P0E15 Control Plane</span><h3>Rebuild, Reconcile And Certify</h3><p>Recovery rebuilds every projection before independent OLTP comparison. Production certification cannot pass without measured scale, latency and zero drift.</p></div><div className="delivery-actions"><button className="btn btn-sm" disabled={recover.isPending} onClick={() => recover.mutate()}>{recover.isPending ? "Recovering…" : "Rebuild And Reconcile"}</button><button className="btn btn-sm" disabled={reconcile.isPending} onClick={() => reconcile.mutate()}>{reconcile.isPending ? "Checking…" : "Reconcile KPIs"}</button><button className="btn btn-sm primary" disabled={certify.isPending} onClick={() => certify.mutate()}>{certify.isPending ? "Certifying…" : "Certify Production"}</button></div></div>
    {latest ? <div className="performance-strip"><Metric label="Certificate" value={title(latest.status)} /><Metric label="Projected Rows" value={latest.projectedRows.toLocaleString()} /><Metric label="30-Day Executions" value={latest.executions} /><Metric label="P95 Query Time" value={latest.p95Ms == null ? "No Evidence" : `${latest.p95Ms} ms`} /><p>{latest.verdict}</p></div> : <div className="empty-studio">No production certificate has been attempted for this tenant.</div>}
  </div>;
}

function Builder() {
  const toasts = useToasts();
  const datasetsQ = useQuery({ queryKey: ["analytics", "datasets"], queryFn: reportingApi.datasets });
  const savedQ = useQuery({ queryKey: ["analytics", "saved-reports"], queryFn: reportingApi.savedReports });
  const [datasetName, setDatasetName] = useState("OPPORTUNITY");
  const [format, setFormat] = useState<"TABULAR" | "SUMMARY" | "MATRIX">("SUMMARY");
  const [columns, setColumns] = useState<string[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [summaries, setSummaries] = useState<Array<{ field: string; function: string; label: string }>>([]);
  const [pivot, setPivot] = useState<string | null>(null);
  const [filterField, setFilterField] = useState("");
  const [filterOperator, setFilterOperator] = useState("EQ");
  const [filterValue, setFilterValue] = useState("");
  const [related, setRelated] = useState("");
  const [relatedMode, setRelatedMode] = useState<"WITH" | "WITHOUT">("WITH");
  const [formulas, setFormulas] = useState<CalculatedMeasure[]>([]);
  const [formulaDraft, setFormulaDraft] = useState<CalculatedMeasure>({ code: "", label: "", formula: "" });
  const [rules, setRules] = useState<ConditionalRule[]>([]);
  const [ruleDraft, setRuleDraft] = useState<ConditionalRule>(EMPTY_RULE);
  const [result, setResult] = useState<ReportResult | null>(null);
  const [drillRows, setDrillRows] = useState<Array<Record<string, unknown>>>([]);
  const [reportName, setReportName] = useState("Pipeline by stage");
  const [reportCode, setReportCode] = useState("pipeline_by_stage");

  const dataset = datasetsQ.data?.find(value => value.name === datasetName);
  useEffect(() => {
    if (!dataset) return;
    const firstText = dataset.fields.find(field => field.groupable)?.name;
    const firstMoney = dataset.fields.find(field => field.summable)?.name;
    setColumns(dataset.fields.slice(0, 5).map(field => field.name));
    setGroups(firstText ? [firstText] : []);
    setSummaries(firstMoney ? [{ field: firstMoney, function: "SUM", label: `Total ${labelOf(dataset.fields, firstMoney)}` }] : []);
    setPivot(dataset.fields.find(field => field.groupable && field.name !== firstText)?.name ?? null);
    setFilterField(firstText ?? "");
    setRelated(""); setResult(null); setDrillRows([]); setFormulas([]); setRules([]);
  }, [datasetName, datasetsQ.data]);

  const definition: ReportRequest = useMemo(() => ({
    dataset: datasetName,
    format,
    columns: format === "TABULAR" ? columns : null,
    groupBy: format === "TABULAR" ? null : groups,
    columnGroup: format === "MATRIX" ? pivot : null,
    summaries: format === "TABULAR" ? summaries : summaries,
    filters: filterField && filterValue ? [{ field: filterField, operator: filterOperator, values: [filterValue] }] : [],
    related: related ? { related, mode: relatedMode, withinDays: 90 } : null,
    calculatedMeasures: formulas,
    conditionalRules: rules,
    // One report-grid page is 100 rows across Axiom. Larger analytical reads
    // belong behind an explicit paged query rather than a browser-side dump.
    limit: 100,
  }), [datasetName, format, columns, groups, pivot, summaries, filterField, filterOperator, filterValue, related, relatedMode, formulas, rules]);

  const run = useMutation({
    mutationFn: () => reportingApi.run(definition),
    onSuccess: value => { setResult(value); setDrillRows([]); toasts.push("info", "Report refreshed", `${value.rowCount} rows in ${value.elapsedMs} ms.`); },
    onError: error => toasts.push("error", "Report could not run", message(error)),
  });
  const save = useMutation({
    mutationFn: () => reportingApi.saveReport(reportCode, reportName, "Built in Axiom Analytics Studio", definition),
    onSuccess: value => { toasts.push("info", "Report saved", `${value.name} is ready for dashboards and sharing.`); void savedQ.refetch(); },
    onError: error => toasts.push("error", "Report could not be saved", message(error)),
  });
  const drill = useMutation({
    mutationFn: () => reportingApi.drill(definition, 50),
    onSuccess: value => { setDrillRows(value.rows.map(row => row.record)); toasts.push("info", "Drill-through checked", `${value.returned} contributing records passed a fresh permission check.`); },
    onError: error => toasts.push("error", "Drill-through unavailable", message(error)),
  });

  function drop(zone: DropZone, event: DragEvent) {
    event.preventDefault();
    const field = event.dataTransfer.getData("text/axiom-report-field");
    if (!field || !dataset) return;
    const meta = dataset.fields.find(value => value.name === field);
    if (!meta) return;
    if (zone === "columns") setColumns(current => unique([...current, field]));
    if (zone === "groups" && meta.groupable) setGroups(current => unique([...current, field]).slice(0, 3));
    if (zone === "pivot" && meta.groupable) setPivot(field);
    if (zone === "measures" && meta.summable) setSummaries(current => current.some(value => value.field === field)
      ? current : [...current, { field, function: "SUM", label: `Total ${labelOf(dataset.fields, field)}` }]);
  }

  function loadSaved(report: SavedReport) {
    const d = report.definition;
    setDatasetName(d.dataset); setFormat(d.format ?? "TABULAR"); setColumns(d.columns ?? []);
    setGroups(d.groupBy ?? []); setPivot(d.columnGroup ?? null); setSummaries(d.summaries?.map(value => ({ ...value, label: value.label ?? value.field })) ?? []);
    setFormulas(d.calculatedMeasures ?? []); setRules(d.conditionalRules ?? []); setReportName(report.name); setReportCode(report.code); setResult(null);
  }

  return <div className="builder-shell">
    <aside className="builder-fields">
      <label className="field"><span>Dataset <Info text="A governed business object. Every field below is approved for reporting." /></span>
        <select value={datasetName} onChange={event => setDatasetName(event.target.value)}>{datasetsQ.data?.map(value => <option key={value.name} value={value.name}>{value.label}</option>)}</select>
      </label>
      <div className="field-search-title"><strong>Fields</strong><small>Drag or click a field</small></div>
      <div className="field-palette">{dataset?.fields.map(field => <button key={field.name} draggable
        onDragStart={event => event.dataTransfer.setData("text/axiom-report-field", field.name)}
        onClick={() => setColumns(current => unique([...current, field.name]))}>
        <span>{field.label}</span><small>{field.kind}</small>
      </button>)}</div>
    </aside>
    <div className="builder-canvas">
      <div className="builder-commandbar">
        <div className="segmented">{(["TABULAR","SUMMARY","MATRIX"] as const).map(value => <button key={value}
          className={format === value ? "active" : ""} onClick={() => setFormat(value)}>{value === "MATRIX" ? "Pivot" : title(value)}</button>)}</div>
        <button className="btn btn-sm primary" disabled={run.isPending} onClick={() => run.mutate()}>{run.isPending ? "Running…" : "Run preview"}</button>
      </div>
      <div className="drop-grid">
        <DropZone title="Detail columns" help="Fields shown in each result row." values={columns} fields={dataset?.fields ?? []}
          onDrop={event => drop("columns", event)} onRemove={field => setColumns(current => current.filter(value => value !== field))} />
        <DropZone title="Row groups" help="How summary rows are grouped." values={groups} fields={dataset?.fields ?? []}
          onDrop={event => drop("groups", event)} onRemove={field => setGroups(current => current.filter(value => value !== field))} />
        <DropZone title="Measures" help="Numbers to aggregate." values={summaries.map(value => value.field)} fields={dataset?.fields ?? []}
          onDrop={event => drop("measures", event)} onRemove={field => setSummaries(current => current.filter(value => value.field !== field))} />
        {format === "MATRIX" && <DropZone title="Pivot columns" help="Values spread across columns." values={pivot ? [pivot] : []} fields={dataset?.fields ?? []}
          onDrop={event => drop("pivot", event)} onRemove={() => setPivot(null)} />}
      </div>
      <ReportPreview result={result} rules={rules} drillRows={drillRows} onDrill={() => drill.mutate()} drilling={drill.isPending} />
    </div>
    <aside className="builder-config">
      <h3>Report settings</h3>
      <label className="field"><span>Name</span><input value={reportName} onChange={event => setReportName(event.target.value)} /></label>
      <label className="field"><span>Code</span><input value={reportCode} onChange={event => setReportCode(slug(event.target.value))} /></label>
      <div className="inline-actions"><button className="btn btn-sm" disabled={save.isPending} onClick={() => save.mutate()}>Save report</button></div>
      <details open><summary>Filter <Info text="Limit the report before grouping. Values are bound safely; no user SQL is accepted." /></summary>
        <label className="field"><span>Field</span><select value={filterField} onChange={event => setFilterField(event.target.value)}>{dataset?.fields.map(field => <option key={field.name} value={field.name}>{field.label}</option>)}</select></label>
        <div className="field-pair"><select aria-label="Filter operator" value={filterOperator} onChange={event => setFilterOperator(event.target.value)}>
          {['EQ','NE','GT','GTE','LT','LTE','CONTAINS'].map(value => <option key={value}>{value}</option>)}</select>
          <input aria-label="Filter value" placeholder="Value" value={filterValue} onChange={event => setFilterValue(event.target.value)} /></div>
      </details>
      {(datasetName === "ACCOUNT" || datasetName === "OPPORTUNITY") && <details><summary>Cross-module relationship</summary>
        <label className="field"><span>Related records</span><select value={related} onChange={event => setRelated(event.target.value)}><option value="">None</option><option value="ACTIVITY">Activities</option>{datasetName === "ACCOUNT" && <option value="OPPORTUNITY">Opportunities</option>}</select></label>
        <select aria-label="Relationship mode" value={relatedMode} onChange={event => setRelatedMode(event.target.value as "WITH" | "WITHOUT")}><option>WITH</option><option>WITHOUT</option></select>
      </details>}
      <details><summary>Calculated measure <Info text="Use safe arithmetic over output fields, for example amount / recordCount. Scripts and SQL are not allowed." /></summary>
        <input placeholder="Code, e.g. avgValue" value={formulaDraft.code} onChange={event => setFormulaDraft(current => ({ ...current, code: slugCamel(event.target.value) }))} />
        <input placeholder="Label" value={formulaDraft.label} onChange={event => setFormulaDraft(current => ({ ...current, label: event.target.value }))} />
        <input placeholder="Formula, e.g. totalAmount / recordCount" value={formulaDraft.formula} onChange={event => setFormulaDraft(current => ({ ...current, formula: event.target.value }))} />
        <button className="btn btn-sm" onClick={() => { if (formulaDraft.code && formulaDraft.label && formulaDraft.formula) { setFormulas(current => [...current, formulaDraft]); setFormulaDraft({ code: "", label: "", formula: "" }); } }}>Add formula</button>
        <TagList values={formulas.map(value => `${value.label}: ${value.formula}`)} onRemove={index => setFormulas(current => current.filter((_, i) => i !== index))} />
      </details>
      <details><summary>Conditional formatting</summary>
        <select aria-label="Formatting field" value={ruleDraft.field} onChange={event => setRuleDraft(current => ({ ...current, field: event.target.value }))}><option value="">Choose output field</option>
          {unique([...columns, ...groups, "recordCount", ...summaries.map(value => value.field), ...formulas.map(value => value.code)]).map(value => <option key={value} value={value}>{value}</option>)}</select>
        <div className="field-pair"><select aria-label="Formatting operator" value={ruleDraft.operator} onChange={event => setRuleDraft(current => ({ ...current, operator: event.target.value as ConditionalRule['operator'] }))}>{['GT','GTE','LT','LTE','EQ','NE','CONTAINS'].map(value => <option key={value}>{value}</option>)}</select>
          <input aria-label="Formatting value" value={ruleDraft.value} onChange={event => setRuleDraft(current => ({ ...current, value: event.target.value }))} /></div>
        <div className="color-pair"><label>Text <input type="color" value={ruleDraft.foreground ?? "#F8FAFC"} onChange={event => setRuleDraft(current => ({ ...current, foreground: event.target.value }))} /></label>
          <label>Fill <input type="color" value={ruleDraft.background ?? "#9A3412"} onChange={event => setRuleDraft(current => ({ ...current, background: event.target.value }))} /></label></div>
        <button className="btn btn-sm" onClick={() => { if (ruleDraft.field) { setRules(current => [...current, ruleDraft]); setRuleDraft(EMPTY_RULE); } }}>Add rule</button>
      </details>
      {savedQ.data && savedQ.data.length > 0 && <details><summary>Saved reports</summary><div className="saved-report-list">{savedQ.data.map(report => <button key={report.id} onClick={() => loadSaved(report)}><strong>{report.name}</strong><small>{report.dataset} · {report.format}</small></button>)}</div></details>}
    </aside>
  </div>;
}

function DashboardDesigner() {
  const toasts = useToasts(); const client = useQueryClient();
  const dashboardsQ = useQuery({ queryKey: ["analytics", "dashboards"], queryFn: reportingApi.dashboards });
  const reportsQ = useQuery({ queryKey: ["analytics", "saved-reports"], queryFn: reportingApi.savedReports });
  const [selected, setSelected] = useState("");
  const [draft, setDraft] = useState({ code: "executive_revenue", name: "Executive revenue", description: "A shared view of revenue health.", layoutMode: "GRID", audience: "PRIVATE", refreshIntervalMinutes: 60 });
  const [widget, setWidget] = useState({ title: "Pipeline by stage", visualizationType: "BAR" as const, reportCode: "", x: 0, y: 0, width: 6, height: 4, configuration: {}, sortOrder: 10 });
  const dashboard = dashboardsQ.data?.find(value => value.code === selected) ?? dashboardsQ.data?.[0];
  useEffect(() => { if (!selected && dashboardsQ.data?.[0]) setSelected(dashboardsQ.data[0].code); }, [dashboardsQ.data, selected]);
  useEffect(() => { if (!widget.reportCode && reportsQ.data?.[0]) setWidget(current => ({ ...current, reportCode: reportsQ.data![0].code })); }, [reportsQ.data, widget.reportCode]);
  const saveDashboard = useMutation({ mutationFn: () => reportingApi.saveDashboard(draft), onSuccess: value => { setSelected(value.code); void client.invalidateQueries({ queryKey: ["analytics", "dashboards"] }); toasts.push("info", "Dashboard saved", "The draft is ready for report-backed widgets."); }, onError: error => toasts.push("error", "Dashboard not saved", message(error)) });
  const saveWidget = useMutation({ mutationFn: () => reportingApi.saveWidget(dashboard!.code, widget), onSuccess: () => { void client.invalidateQueries({ queryKey: ["analytics", "dashboards"] }); toasts.push("info", "Widget added", "The 12-column layout has been updated."); }, onError: error => toasts.push("error", "Widget not added", message(error)) });
  return <div className="dashboard-designer">
    <aside className="dashboard-list"><div className="section-label">Your dashboards</div>{dashboardsQ.data?.map(value => <button key={value.id} className={dashboard?.id === value.id ? "active" : ""} onClick={() => setSelected(value.code)}><strong>{value.name}</strong><small>{value.widgets.length} widgets · {value.audience}</small></button>)}</aside>
    <div className="dashboard-stage">
      <div className="dashboard-stage-head"><div><span className="eyebrow">12-column canvas</span><h3>{dashboard?.name ?? "Create a dashboard"}</h3><p>{dashboard?.description}</p></div><span className="scope-badge">{dashboard?.status ?? "DRAFT"}</span></div>
      {dashboard ? <div className="widget-grid">{dashboard.widgets.map(value => <article key={value.id} className="dashboard-widget" style={{ "--x": value.x, "--w": value.width, "--h": value.height } as CSSProperties}><span className="eyebrow">{value.visualizationType}</span><h4>{value.title}</h4><p>Backed by <strong>{value.reportCode ?? value.title}</strong></p><div className={`widget-placeholder ${value.visualizationType.toLowerCase()}`}><i /><i /><i /><i /></div></article>)}{dashboard.widgets.length === 0 && <div className="empty-studio">Add a saved report as the first widget. Layout dimensions are validated server-side.</div>}</div> : <div className="empty-studio">Create your first dashboard from the configuration panel.</div>}
    </div>
    <aside className="dashboard-config"><h3>Designer</h3>
      <details open><summary>Dashboard</summary><input value={draft.name} onChange={e => setDraft(v => ({ ...v, name: e.target.value }))} placeholder="Dashboard name" /><input value={draft.code} onChange={e => setDraft(v => ({ ...v, code: slug(e.target.value) }))} placeholder="Code" /><textarea value={draft.description} onChange={e => setDraft(v => ({ ...v, description: e.target.value }))} /><div className="field-pair"><select value={draft.layoutMode} onChange={e => setDraft(v => ({ ...v, layoutMode: e.target.value }))}><option>GRID</option><option>FREEFORM</option></select><select value={draft.audience} onChange={e => setDraft(v => ({ ...v, audience: e.target.value }))}><option>PRIVATE</option><option>SHARED</option><option>TENANT</option></select></div><button className="btn btn-sm" onClick={() => saveDashboard.mutate()}>Save dashboard</button></details>
      {dashboard && <details open><summary>Add report widget</summary><input value={widget.title} onChange={e => setWidget(v => ({ ...v, title: e.target.value }))} placeholder="Widget title" /><select value={widget.reportCode} onChange={e => setWidget(v => ({ ...v, reportCode: e.target.value }))}>{reportsQ.data?.map(report => <option key={report.id} value={report.code}>{report.name}</option>)}</select><select value={widget.visualizationType} onChange={e => setWidget(v => ({ ...v, visualizationType: e.target.value as typeof widget.visualizationType }))}>{['KPI','BAR','LINE','AREA','DONUT','FUNNEL','TABLE','PIVOT','SUMMARY'].map(value => <option key={value}>{value}</option>)}</select><div className="field-pair"><label>Width<input type="number" min="1" max="12" value={widget.width} onChange={e => setWidget(v => ({ ...v, width: Number(e.target.value) }))} /></label><label>Height<input type="number" min="1" max="12" value={widget.height} onChange={e => setWidget(v => ({ ...v, height: Number(e.target.value) }))} /></label></div><button className="btn btn-sm primary" disabled={!widget.reportCode} onClick={() => saveWidget.mutate()}>Add to canvas</button></details>}
    </aside>
  </div>;
}

function DeliveryWorkspace() {
  const toasts = useToasts(); const client = useQueryClient();
  const reportsQ = useQuery({ queryKey: ["analytics", "saved-reports"], queryFn: reportingApi.savedReports });
  const sharesQ = useQuery({ queryKey: ["analytics", "shares"], queryFn: reportingApi.shares });
  const deliveriesQ = useQuery({ queryKey: ["analytics", "deliveries"], queryFn: reportingApi.deliveries });
  const embedsQ = useQuery({ queryKey: ["analytics", "embeds"], queryFn: reportingApi.embeds });
  const performanceQ = useQuery({ queryKey: ["analytics", "performance"], queryFn: reportingApi.performance });
  const [target, setTarget] = useState(""); const [recipient, setRecipient] = useState("superadmin@axiomcrm.com");
  const [commentBody, setCommentBody] = useState("Please review the assumptions behind this report.");
  const commentsQ = useQuery({ queryKey: ["analytics", "comments", target], queryFn: () => reportingApi.comments("REPORT", target), enabled: Boolean(target) });
  useEffect(() => { if (!target && reportsQ.data?.[0]) setTarget(reportsQ.data[0].code); }, [reportsQ.data, target]);
  const share = useMutation({ mutationFn: () => reportingApi.share({ targetType: "REPORT", targetCode: target, principalType: "ROLE", principalKey: "SALES_MANAGER", permission: "VIEW" }), onSuccess: () => { void client.invalidateQueries({ queryKey: ["analytics", "shares"] }); toasts.push("info", "Report shared", "Sales managers can now view this report under their own record access."); }, onError: e => toasts.push("error", "Share failed", message(e)) });
  const delivery = useMutation({ mutationFn: () => reportingApi.scheduleDelivery({ targetType: "REPORT", targetCode: target, name: `${target} weekly delivery`, artifactFormat: "PDF", frequency: "WEEKLY", recipients: [recipient] }), onSuccess: () => { void client.invalidateQueries({ queryKey: ["analytics", "deliveries"] }); toasts.push("info", "Delivery policy saved", "Attachment generation is governed; vendor mail transport remains pending."); }, onError: e => toasts.push("error", "Delivery failed", message(e)) });
  const threshold = useMutation({ mutationFn: () => reportingApi.scheduleDelivery({ targetType: "REPORT", targetCode: target, name: `${target} coverage threshold`, artifactFormat: "LINK", frequency: "THRESHOLD", recipients: [recipient], thresholdMetricCode: "PIPELINE_COVERAGE", thresholdOperator: "LT", thresholdValue: 3 }), onSuccess: () => { void client.invalidateQueries({ queryKey: ["analytics", "deliveries"] }); toasts.push("info", "Threshold alert saved", "The policy uses the governed KPI definition."); }, onError: e => toasts.push("error", "Alert failed", message(e)) });
  const embed = useMutation({ mutationFn: () => reportingApi.createEmbed({ targetType: "REPORT", targetCode: target, embedCode: `${target}-embed`, allowedOrigins: [window.location.origin] }), onSuccess: () => { void client.invalidateQueries({ queryKey: ["analytics", "embeds"] }); toasts.push("info", "Embed created", "The embedded view requires an authenticated Axiom session."); }, onError: e => toasts.push("error", "Embed failed", message(e)) });
  const comment = useMutation({ mutationFn: () => reportingApi.comment({ targetType: "REPORT", targetCode: target, body: commentBody }), onSuccess: () => { void client.invalidateQueries({ queryKey: ["analytics", "comments", target] }); setCommentBody(""); toasts.push("info", "Comment added", "The discussion is attached to this governed report definition."); }, onError: e => toasts.push("error", "Comment failed", message(e)) });
  return <div className="governance-workspace">
    <div className="governance-compose"><div><span className="eyebrow">Governed distribution</span><h3>One access model, every channel</h3><p>Shares recalculate for each viewer. Embeds require login. Thresholds reference the governed KPI registry.</p></div><div className="delivery-form"><select aria-label="Content to distribute" value={target} onChange={e => setTarget(e.target.value)}>{reportsQ.data?.map(report => <option key={report.id} value={report.code}>{report.name}</option>)}</select><input aria-label="Delivery recipient" value={recipient} onChange={e => setRecipient(e.target.value)} /><div className="delivery-actions"><button className="btn btn-sm" onClick={() => share.mutate()}>Share with managers</button><button className="btn btn-sm" onClick={() => delivery.mutate()}>Schedule PDF</button><button className="btn btn-sm" onClick={() => threshold.mutate()}>Alert below 3×</button><button className="btn btn-sm" onClick={() => embed.mutate()}>Create embed</button></div></div></div>
    {performanceQ.data && <div className="performance-strip"><Metric label="30-day executions" value={performanceQ.data.executions} /><Metric label="P95 query time" value={`${performanceQ.data.p95Ms} ms`} /><Metric label="Timeouts" value={performanceQ.data.timeouts} /><Metric label="Capped results" value={performanceQ.data.truncated} /><p>{performanceQ.data.assessment}</p></div>}
    <div className="collaboration-compose"><input aria-label="Report comment" value={commentBody} onChange={event => setCommentBody(event.target.value)} placeholder="Add a decision, question or review note" /><button className="btn btn-sm" disabled={!commentBody.trim() || comment.isPending} onClick={() => comment.mutate()}>Add comment</button></div>
    <div className="governance-columns"><GovernanceList title="Active shares" empty="No report collaboration rules yet." rows={sharesQ.data?.map(value => ({ title: `${value.targetCode} · ${value.permission}`, detail: `${value.principalType.toLowerCase()} ${value.principalKey}` })) ?? []} /><GovernanceList title="Discussion" empty="No review comments yet." rows={commentsQ.data?.map(value => ({ title: value.body, detail: new Date(value.createdAt).toLocaleString() })) ?? []} /><GovernanceList title="Delivery & alerts" empty="No delivery policies yet." rows={deliveriesQ.data?.map(value => ({ title: `${value.name} · ${value.frequency}`, detail: `${value.artifactFormat} · ${value.deliveryState}` })) ?? []} /><GovernanceList title="Embedded analytics" empty="No authenticated embeds yet." rows={embedsQ.data?.map(value => ({ title: value.embedCode, detail: `${value.relativeUrl} · login required` })) ?? []} /></div>
  </div>;
}

function ReportPreview({ result, rules, drillRows, onDrill, drilling }: { result: ReportResult | null; rules: ConditionalRule[]; drillRows: Array<Record<string, unknown>>; onDrill: () => void; drilling: boolean }) {
  if (!result) return <div className="report-preview empty"><span className="eyebrow">Live preview</span><h3>Your governed result appears here</h3><p>Choose fields, groups and measures, then run the report. No analytical query runs while you are arranging the canvas.</p></div>;
  return <div className="report-preview"><div className="preview-head"><div><span className="eyebrow">Preview · {result.format}</span><h3>{result.rowCount} rows <small>as of {result.staleness.asOf ? new Date(result.staleness.asOf).toLocaleString() : "no projected data"}</small></h3></div><div><span className={`freshness ${result.staleness.degraded ? "warn" : ""}`}>{formatLag(result.staleness.lagSeconds)} lag</span><button className="btn btn-sm" disabled={drilling} onClick={onDrill}>{drilling ? "Checking…" : "Drill into records"}</button></div></div>
    {result.guidance && <p className="studio-guidance">{result.guidance}</p>}
    <div className="table-wrap"><table className="data-table studio-result"><thead><tr>{result.columns.map(column => <th key={column.field}>{column.label}<small>{column.role}</small></th>)}</tr></thead><tbody>{result.rows.map((row, index) => <tr key={index}>{result.columns.map(column => <td key={column.field} style={cellStyle(row[column.field], rules.filter(rule => rule.field === column.field))}>{formatCell(row[column.field], column.kind)}</td>)}</tr>)}</tbody>{Object.keys(result.grandTotals).length > 0 && <tfoot><tr>{result.columns.map((column, index) => <td key={column.field}>{index === 0 ? "Grand total" : formatCell(result.grandTotals[column.field], column.kind)}</td>)}</tr></tfoot>}</table></div>
    {drillRows.length > 0 && <details open className="drill-results"><summary>Authorized contributing records ({drillRows.length})</summary><pre>{JSON.stringify(drillRows.slice(0, 8), null, 2)}</pre></details>}
  </div>;
}

function DropZone({ title: zoneTitle, help, values, fields, onDrop, onRemove }: { title: string; help: string; values: string[]; fields: DatasetField[]; onDrop: (event: DragEvent) => void; onRemove: (field: string) => void }) {
  return <section className="drop-zone" onDragOver={event => event.preventDefault()} onDrop={onDrop}><strong>{zoneTitle}</strong><small>{help}</small><div>{values.map(value => <button key={value} onClick={() => onRemove(value)} title="Remove">{labelOf(fields, value)} <span>×</span></button>)}{values.length === 0 && <em>Drop a compatible field here</em>}</div></section>;
}
function TagList({ values, onRemove }: { values: string[]; onRemove: (index: number) => void }) { return <div className="tag-list">{values.map((value, index) => <button key={`${value}-${index}`} onClick={() => onRemove(index)}>{value} ×</button>)}</div>; }
function Info({ text }: { text: string }) { return <InfoTag text={text} label="Report field help" />; }
function Metric({ label, value }: { label: string; value: string | number }) { return <div><strong>{value}</strong><span>{label}</span></div>; }
function GovernanceList({ title: listTitle, rows, empty }: { title: string; rows: Array<{ title: string; detail: string }>; empty: string }) { return <section><h3>{listTitle}</h3>{rows.length ? rows.map((row, index) => <article key={`${row.title}-${index}`}><strong>{row.title}</strong><small>{row.detail}</small></article>) : <p>{empty}</p>}</section>; }

function cellStyle(value: unknown, rules: ConditionalRule[]): CSSProperties | undefined { const rule = rules.find(item => matches(value, item)); return rule ? { color: rule.foreground ?? undefined, background: rule.background ?? undefined } : undefined; }
function matches(value: unknown, rule: ConditionalRule) { const left = Number(value); const right = Number(rule.value); if (rule.operator === "CONTAINS") return String(value ?? "").toLowerCase().includes(rule.value.toLowerCase()); if (rule.operator === "EQ") return String(value) === rule.value; if (rule.operator === "NE") return String(value) !== rule.value; if (!Number.isFinite(left) || !Number.isFinite(right)) return false; return rule.operator === "GT" ? left > right : rule.operator === "GTE" ? left >= right : rule.operator === "LT" ? left < right : left <= right; }
function labelOf(fields: DatasetField[], name: string) { return fields.find(value => value.name === name)?.label ?? name; }
function unique(values: string[]) { return [...new Set(values)]; }
function title(value: string) { return value.charAt(0) + value.slice(1).toLowerCase(); }
function slug(value: string) { return value.toLowerCase().trim().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, ""); }
function slugCamel(value: string) { const parts = value.trim().replace(/[^a-zA-Z0-9]+/g, " ").split(/\s+/); return parts.map((part, index) => index ? part.charAt(0).toUpperCase() + part.slice(1) : part.charAt(0).toLowerCase() + part.slice(1)).join(""); }
function message(error: unknown) { return error instanceof Error ? error.message : "The request could not be completed."; }
