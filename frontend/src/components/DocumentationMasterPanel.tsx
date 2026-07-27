import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api,
  type DocumentationEntryMutation,
  type DocumentationMasterEntry,
  type DocumentationMasterSection,
  type DocumentationSectionMutation,
} from "../api/client";
import { DataGridToolbar } from "./DataGridToolbar";
import { DataViewFrame } from "./DataViewFrame";
import { useGridDataLoad } from "./PageDataGate";
import { useToasts } from "./Toasts";

const LOCALES = ["en", "de", "ru"] as const;
const TYPES = ["CALLOUT", "STEPS", "SHORTCUTS", "RULE"] as const;

const emptySection: DocumentationSectionMutation = {
  code: "", type: "STEPS", sortOrder: 10, active: true,
  headings: { en: "", de: "", ru: "" }, changeNote: "Added documentation section",
};
const emptyEntry: DocumentationEntryMutation = {
  sectionId: "", code: "", marker: "", sortOrder: 10, active: true,
  translations: {
    en: { title: "", body: "" }, de: { title: "", body: "" }, ru: { title: "", body: "" },
  },
  changeNote: "Added documentation entry",
};

/** Administrator-facing master for all content rendered in HelpDrawer. */
export function DocumentationMasterPanel({ readOnly }: { readOnly: boolean }) {
  const documentationGrid = useGridDataLoad("Documentation Drawer Master");
  const queryClient = useQueryClient();
  const toasts = useToasts();
  const masterQ = useQuery({ queryKey: ["documentation", "master"], queryFn: () => api.documentationMaster(true), enabled: documentationGrid.loaded, retry: 1 });
  const [selectedSection, setSelectedSection] = useState<string | null>(null);
  const [sectionDraft, setSectionDraft] = useState<DocumentationSectionMutation>(emptySection);
  const [selectedEntry, setSelectedEntry] = useState<string | null>(null);
  const [entryDraft, setEntryDraft] = useState<DocumentationEntryMutation>(emptyEntry);
  const [drawerDraft, setDrawerDraft] = useState({
    translations: { en: { eyebrow: "", title: "" }, de: { eyebrow: "", title: "" }, ru: { eyebrow: "", title: "" } },
    active: true, changeNote: "Updated documentation drawer settings",
  });

  const sections = masterQ.data?.sections ?? [];
  const entries = useMemo(() => sections.flatMap((section) => section.entries.map((entry) => ({ section, entry }))), [sections]);
  const exportRows = entries.map(({ section, entry }) => ({
    section: section.code, type: section.type, code: entry.code, marker: entry.marker ?? "",
    title: entry.translations.en?.title ?? "", order: entry.sortOrder, status: entry.active ? "Active" : "Inactive",
  }));

  useEffect(() => {
    if (!entryDraft.sectionId && sections[0]) setEntryDraft((value) => ({ ...value, sectionId: sections[0].id }));
  }, [entryDraft.sectionId, sections]);
  useEffect(() => {
    if (!masterQ.data) return;
    setDrawerDraft({
      translations: Object.fromEntries(LOCALES.map((locale) => [locale, {
        eyebrow: masterQ.data?.translations[locale]?.eyebrow ?? masterQ.data?.translations.en?.eyebrow ?? "",
        title: masterQ.data?.translations[locale]?.title ?? masterQ.data?.translations.en?.title ?? "",
      }])) as typeof drawerDraft.translations,
      active: masterQ.data.active,
      changeNote: "Updated documentation drawer settings",
    });
  }, [masterQ.data]);

  function refreshed(message: string) {
    toasts.push("info", "Documentation master saved", message);
    void queryClient.invalidateQueries({ queryKey: ["documentation"] });
  }
  function failed(error: unknown) {
    toasts.push("error", "Documentation was not saved", error instanceof Error ? error.message : "Save failed.");
  }
  const saveSection = useMutation({
    mutationFn: () => selectedSection
      ? api.updateDocumentationSection(selectedSection, sectionDraft)
      : api.createDocumentationSection(sectionDraft),
    onSuccess: () => { refreshed("The drawer section and revision history were updated."); resetSection(); },
    onError: failed,
  });
  const saveDrawer = useMutation({
    mutationFn: () => api.updateDocumentationMaster(drawerDraft),
    onSuccess: () => refreshed("The drawer title, lifecycle and revision history were updated."),
    onError: failed,
  });
  const saveEntry = useMutation({
    mutationFn: () => selectedEntry
      ? api.updateDocumentationEntry(selectedEntry, entryDraft)
      : api.createDocumentationEntry(entryDraft),
    onSuccess: () => { refreshed("The drawer entry and revision history were updated."); resetEntry(); },
    onError: failed,
  });
  const toggleSection = useMutation({
    mutationFn: (section: DocumentationMasterSection) => api.updateDocumentationSection(section.id, {
      code: section.code, type: section.type, sortOrder: section.sortOrder, active: !section.active,
      headings: headings(section), changeNote: `${section.active ? "Inactivated" : "Activated"} documentation section ${section.code}`,
    }),
    onSuccess: () => refreshed("The section lifecycle was updated without deleting its history."), onError: failed,
  });
  const toggleEntry = useMutation({
    mutationFn: ({ section, entry }: { section: DocumentationMasterSection; entry: DocumentationMasterEntry }) => api.updateDocumentationEntry(entry.id, {
      sectionId: section.id, code: entry.code, marker: entry.marker ?? "", sortOrder: entry.sortOrder, active: !entry.active,
      translations: translations(entry), changeNote: `${entry.active ? "Inactivated" : "Activated"} documentation entry ${entry.code}`,
    }),
    onSuccess: () => refreshed("The entry lifecycle was updated without deleting its history."), onError: failed,
  });

  function resetSection() { setSelectedSection(null); setSectionDraft(emptySection); }
  function resetEntry() {
    setSelectedEntry(null);
    setEntryDraft({ ...emptyEntry, sectionId: sections[0]?.id ?? "" });
  }
  function editSection(section: DocumentationMasterSection) {
    setSelectedSection(section.id);
    setSectionDraft({ code: section.code, type: section.type, sortOrder: section.sortOrder,
      active: section.active, headings: headings(section), changeNote: `Updated documentation section ${section.code}` });
  }
  function editEntry(section: DocumentationMasterSection, entry: DocumentationMasterEntry) {
    setSelectedEntry(entry.id);
    setEntryDraft({ sectionId: section.id, code: entry.code, marker: entry.marker ?? "",
      sortOrder: entry.sortOrder, active: entry.active, translations: translations(entry),
      changeNote: `Updated documentation entry ${entry.code}` });
  }

  if (masterQ.isLoading) return <div className="empty-note">Loading documentation master…</div>;
  if (masterQ.isError) return <div className="empty-note">Documentation master could not be loaded. <button className="link-btn" onClick={() => void masterQ.refetch()}>Retry</button></div>;

  return <DataViewFrame title="Documentation Drawer Master" actions={<DataGridToolbar
    gridName="Documentation Drawer Master" auditEntityType="DOCUMENTATION_DRAWER"
    exportFilename="documentation-drawer-master" exportRows={exportRows}
    note={`Version ${masterQ.data?.version ?? 1} · ${sections.length} sections · ${entries.length} entries`}
  />}>
    {!readOnly && <div className="documentation-master-editors">
      <section className="config-card documentation-editor documentation-drawer-editor">
        <div className="documentation-editor-head"><h2>Drawer Settings</h2><label className="check-line"><input type="checkbox" checked={drawerDraft.active} onChange={(event) => setDrawerDraft((value) => ({ ...value, active: event.target.checked }))} /> Active</label></div>
        <div className="documentation-locale-grid">{LOCALES.map((locale) => <fieldset key={locale}><legend>{locale.toUpperCase()} Drawer</legend>
          <input aria-label={`${locale} drawer eyebrow`} value={drawerDraft.translations[locale].eyebrow} onChange={(event) => setDrawerDraft((value) => ({ ...value, translations: { ...value.translations, [locale]: { ...value.translations[locale], eyebrow: event.target.value } } }))} placeholder="Field manual · 01" />
          <input aria-label={`${locale} drawer title`} value={drawerDraft.translations[locale].title} onChange={(event) => setDrawerDraft((value) => ({ ...value, translations: { ...value.translations, [locale]: { ...value.translations[locale], title: event.target.value } } }))} placeholder="User Manual" />
        </fieldset>)}</div>
        <label>Change Note<input value={drawerDraft.changeNote} onChange={(event) => setDrawerDraft((value) => ({ ...value, changeNote: event.target.value }))} /></label>
        <button className="btn btn-primary btn-sm" disabled={saveDrawer.isPending} onClick={() => saveDrawer.mutate()}>Save Drawer Settings</button>
      </section>
      <section className="config-card documentation-editor">
        <div className="documentation-editor-head"><h2>{selectedSection ? "Edit Section" : "Add Section"}</h2>{selectedSection && <button className="link-btn" onClick={resetSection}>New</button>}</div>
        <label>Section Code<input value={sectionDraft.code} onChange={(event) => setSectionDraft((value) => ({ ...value, code: event.target.value }))} placeholder="CORE_LOOP" /></label>
        <label>Layout Type<select value={sectionDraft.type} onChange={(event) => setSectionDraft((value) => ({ ...value, type: event.target.value as DocumentationSectionMutation["type"] }))}>{TYPES.map((type) => <option key={type}>{type}</option>)}</select></label>
        <label>Display Order<input type="number" min="1" value={sectionDraft.sortOrder} onChange={(event) => setSectionDraft((value) => ({ ...value, sortOrder: Number(event.target.value) }))} /></label>
        {LOCALES.map((locale) => <label key={locale}>{locale.toUpperCase()} Heading<input value={sectionDraft.headings[locale] ?? ""} onChange={(event) => setSectionDraft((value) => ({ ...value, headings: { ...value.headings, [locale]: event.target.value } }))} /></label>)}
        <label>Change Note<input value={sectionDraft.changeNote} onChange={(event) => setSectionDraft((value) => ({ ...value, changeNote: event.target.value }))} /></label>
        <button className="btn btn-primary btn-sm" disabled={saveSection.isPending} onClick={() => saveSection.mutate()}>{selectedSection ? "Save Section" : "Add Section"}</button>
      </section>
      <section className="config-card documentation-editor">
        <div className="documentation-editor-head"><h2>{selectedEntry ? "Edit Entry" : "Add Entry"}</h2>{selectedEntry && <button className="link-btn" onClick={resetEntry}>New</button>}</div>
        <label>Section<select value={entryDraft.sectionId} onChange={(event) => setEntryDraft((value) => ({ ...value, sectionId: event.target.value }))}>{sections.map((section) => <option key={section.id} value={section.id}>{section.code}</option>)}</select></label>
        <label>Entry Code<input value={entryDraft.code} onChange={(event) => setEntryDraft((value) => ({ ...value, code: event.target.value }))} placeholder="SCAN_HOME" /></label>
        <div className="documentation-inline-fields"><label>Marker<input value={entryDraft.marker ?? ""} onChange={(event) => setEntryDraft((value) => ({ ...value, marker: event.target.value }))} placeholder="01" /></label><label>Display Order<input type="number" min="1" value={entryDraft.sortOrder} onChange={(event) => setEntryDraft((value) => ({ ...value, sortOrder: Number(event.target.value) }))} /></label></div>
        {LOCALES.map((locale) => <fieldset key={locale}><legend>{locale.toUpperCase()} Content</legend>
          <input aria-label={`${locale} title`} value={entryDraft.translations[locale]?.title ?? ""} onChange={(event) => setEntryDraft((value) => ({ ...value, translations: { ...value.translations, [locale]: { ...value.translations[locale], title: event.target.value } } }))} placeholder="Title" />
          <textarea aria-label={`${locale} body`} value={entryDraft.translations[locale]?.body ?? ""} onChange={(event) => setEntryDraft((value) => ({ ...value, translations: { ...value.translations, [locale]: { ...value.translations[locale], body: event.target.value } } }))} placeholder="Plain-language guidance" />
        </fieldset>)}
        <label>Change Note<input value={entryDraft.changeNote} onChange={(event) => setEntryDraft((value) => ({ ...value, changeNote: event.target.value }))} /></label>
        <button className="btn btn-primary btn-sm" disabled={saveEntry.isPending || !entryDraft.sectionId} onClick={() => saveEntry.mutate()}>{selectedEntry ? "Save Entry" : "Add Entry"}</button>
      </section>
    </div>}

    <div className="table-wrap"><table className="data-table"><thead><tr><th>Section</th><th>Layout Type</th><th>Entry</th><th>Marker</th><th>English Title</th><th>Order</th><th>Status</th>{!readOnly && <th className="table-action">Action</th>}</tr></thead>
      <tbody>{entries.map(({ section, entry }) => <tr key={entry.id}><td>{section.code}</td><td>{section.type}</td><td>{entry.code}</td><td>{entry.marker ?? "—"}</td><td>{entry.translations.en?.title ?? "Missing"}</td><td>{section.sortOrder}.{entry.sortOrder}</td><td>{entry.active && section.active ? "Active" : "Inactive"}</td>{!readOnly && <td className="table-action documentation-row-actions"><button className="link-btn" onClick={() => editSection(section)}>Edit Section</button><button className="link-btn" onClick={() => editEntry(section, entry)}>Edit Entry</button><button className="link-btn" onClick={() => toggleEntry.mutate({ section, entry })}>{entry.active ? "Inactivate Entry" : "Activate Entry"}</button>{section.entries[0]?.id === entry.id && <button className="link-btn" onClick={() => toggleSection.mutate(section)}>{section.active ? "Inactivate Section" : "Activate Section"}</button>}</td>}</tr>)}
      {entries.length === 0 && <tr><td colSpan={readOnly ? 7 : 8} className="empty-note">No documentation entries are configured.</td></tr>}</tbody>
    </table></div>
  </DataViewFrame>;
}

function headings(section: DocumentationMasterSection): Record<string, string> {
  return Object.fromEntries(LOCALES.map((locale) => [locale, section.headings[locale] ?? ""]));
}
function translations(entry: DocumentationMasterEntry) {
  return Object.fromEntries(LOCALES.map((locale) => [locale, {
    title: entry.translations[locale]?.title ?? entry.translations.en?.title ?? "",
    body: entry.translations[locale]?.body ?? "",
  }]));
}
