import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, type SavedView, type SavedViewDefinition } from "../api/client";
import { InfoTag } from "./InfoTag";
import { InlineLoader } from "./Loaders";
import { useToasts } from "./Toasts";
import { useAppDialog } from "./AppDialog";

/**
 * The saved-view control for a grid: apply, save, share, delete.
 *
 * <p>Rendered as a fourth `grid-tool-row`, so its label lands on the same gutter
 * as Actions, Group and Column search. That is the point of the shared gutter —
 * a new row added months later lines up without touching any other row.
 *
 * <h2>Applying a view replaces the grid's state, and says so</h2>
 * Not merges. A view is a saved answer to "how do I want this list arranged", and
 * half-applying it — keeping the filters the user happens to have typed — produces
 * an arrangement that is neither the saved one nor the one they had. The button
 * label is "Apply", and the toast names what changed.
 *
 * <h2>A shared view someone else owns is read-only here</h2>
 * The server refuses the edit; showing an enabled Save that then 403s would be a
 * worse experience than not offering it. `editable` comes back on every row for
 * exactly this, and Save-as-new-copy is offered in its place.
 */
interface SavedViewBarProps {
  /** Same key `usePersistedGridState` uses, so a view and the grid describe one object. */
  gridKey: string;
  /** The grid's current state, captured when the user saves. */
  currentState: SavedViewDefinition;
  /** Replaces the grid's state with the view's. */
  onApply: (definition: SavedViewDefinition) => void;
}

export function SavedViewBar({ gridKey, currentState, onApply }: SavedViewBarProps) {
  const toasts = useToasts();
  const dialog = useAppDialog();
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState("");
  const [saving, setSaving] = useState(false);
  const [name, setName] = useState("");
  const [shared, setShared] = useState(false);
  const [makeDefault, setMakeDefault] = useState(false);

  const viewsQ = useQuery({
    queryKey: ["saved-views", gridKey],
    queryFn: () => api.savedViews(gridKey),
    retry: 1,
  });

  const views = viewsQ.data ?? [];
  const selected = views.find((view) => view.id === selectedId);

  /*
   * Apply the user's default once, on first load. Deliberately not on every
   * refetch: re-applying after each save would throw away whatever they changed
   * since, which is the opposite of what saving is for.
   */
  const [defaultApplied, setDefaultApplied] = useState(false);
  useEffect(() => {
    if (defaultApplied || !viewsQ.isSuccess) return;
    setDefaultApplied(true);
    const fallback = views.find((view) => view.isDefault);
    if (fallback) {
      setSelectedId(fallback.id);
      onApply(fallback.definition);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewsQ.isSuccess, defaultApplied]);

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["saved-views", gridKey] });
  }

  const saveMutation = useMutation({
    mutationFn: () => api.createSavedView({
      gridKey,
      name: name.trim(),
      visibility: shared ? "SHARED" : "PRIVATE",
      definition: currentState,
      isDefault: makeDefault,
    }),
    onSuccess: (view) => {
      toasts.push("info", "View saved", `"${view.name}" is ${view.visibility === "SHARED"
        ? "shared with this workspace" : "private to you"}${view.isDefault ? " and opens by default" : ""}.`);
      setSelectedId(view.id);
      setSaving(false);
      setName("");
      setMakeDefault(false);
      invalidate();
    },
    onError: (error) => toasts.push("error", "View not saved", describe(error)),
  });

  const overwriteMutation = useMutation({
    mutationFn: (view: SavedView) => api.updateSavedView(view.id, view.version, {
      gridKey,
      name: view.name,
      description: view.description,
      visibility: view.visibility,
      definition: currentState,
      isDefault: view.isDefault,
    }),
    onSuccess: (view) => {
      toasts.push("info", "View updated", `"${view.name}" now stores the current arrangement.`);
      invalidate();
    },
    onError: (error) => toasts.push("error", "View not updated", describe(error)),
  });

  const deleteMutation = useMutation({
    mutationFn: (view: SavedView) => api.deleteSavedView(view.id),
    onSuccess: () => {
      toasts.push("info", "View deleted", "The saved arrangement was removed. Your grid is unchanged.");
      setSelectedId("");
      invalidate();
    },
    onError: (error) => toasts.push("error", "View not deleted", describe(error)),
  });

  function apply(id: string) {
    setSelectedId(id);
    const view = views.find((candidate) => candidate.id === id);
    if (!view) return;
    onApply(view.definition);
    const groups = view.definition.groupColumns?.length ?? 0;
    const filters = Object.keys(view.definition.columnFilters ?? {}).length;
    toasts.push("info", `Applied "${view.name}"`,
      `${filters} column filter${filters === 1 ? "" : "s"} and ${groups} grouping${groups === 1 ? "" : "s"}.`);
  }

  return (
    <div className="grid-tool-row saved-view-row" role="group" aria-label="Saved views">
      <div className="grid-tool-label">
        <span>Views</span>
        <InfoTag
          text="A saved view stores this grid's filters, grouping and sort under a name. Shared views can be applied by anyone in the workspace but changed only by their owner."
          label="Saved views help"
        />
      </div>

      <div className="grid-tool-controls">
        {viewsQ.isLoading && <InlineLoader label="Loading views" />}

        {viewsQ.isSuccess && (
          <>
            <select
              aria-label="Apply a saved view"
              value={selectedId}
              onChange={(event) => apply(event.target.value)}
              disabled={views.length === 0}
            >
              <option value="">
                {views.length === 0 ? "No saved views yet" : "Choose a view…"}
              </option>
              {views.map((view) => (
                <option key={view.id} value={view.id}>
                  {view.name}
                  {view.isDefault ? " · default" : ""}
                  {view.visibility === "SHARED" ? ` · shared by ${view.ownerName ?? "another user"}` : ""}
                </option>
              ))}
            </select>

            {!saving && (
              <button type="button" className="btn btn-sm" onClick={() => setSaving(true)}>
                Save current view
              </button>
            )}

            {selected && selected.editable && (
              <button type="button" className="btn btn-sm"
                disabled={overwriteMutation.isPending}
                onClick={() => overwriteMutation.mutate(selected)}>
                {overwriteMutation.isPending ? <InlineLoader label="Updating" /> : "Overwrite"}
              </button>
            )}

            {selected && selected.editable && (
              <button type="button" className="btn btn-sm danger-link"
                disabled={deleteMutation.isPending}
                onClick={async () => {
                  const confirmed = await dialog.confirm({
                    title: "Delete Saved View",
                    message: `Delete the saved view "${selected.name}"? Your current grid arrangement is not affected.`,
                    confirmLabel: "Delete View",
                    tone: "danger",
                  });
                  if (confirmed) deleteMutation.mutate(selected);
                }}>
                Delete
              </button>
            )}

            {/* A shared view owned by somebody else: the server will refuse an
                edit, so offer the thing that does work instead of a button that
                fails. */}
            {selected && !selected.editable && (
              <span className="grid-view-summary">
                Shared by {selected.ownerName ?? "another user"} · save your own copy to change it
              </span>
            )}
          </>
        )}

        {viewsQ.isError && (
          <span className="form-error" role="alert">Saved views could not be loaded.</span>
        )}
      </div>

      <div className="grid-tool-trailing">
        {saving && (
          <form
            className="saved-view-form"
            onSubmit={(event) => { event.preventDefault(); saveMutation.mutate(); }}
          >
            <input
              autoFocus
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Name this view"
              aria-label="Saved view name"
              required
            />
            <label className="saved-view-toggle">
              <input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} />
              <span>Share</span>
            </label>
            <label className="saved-view-toggle">
              <input type="checkbox" checked={makeDefault}
                onChange={(e) => setMakeDefault(e.target.checked)} />
              <span>Default</span>
            </label>
            <button type="submit" className="btn btn-sm btn-primary"
              disabled={saveMutation.isPending || !name.trim()}>
              {saveMutation.isPending ? <InlineLoader label="Saving" /> : "Save"}
            </button>
            <button type="button" className="btn btn-sm"
              onClick={() => { setSaving(false); setName(""); }}>
              Cancel
            </button>
          </form>
        )}
      </div>
    </div>
  );
}

/**
 * The server's own wording wherever it sent some. Its messages name the view, the
 * owner and the way forward; a generic "request failed" throws that away.
 */
function describe(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return error instanceof Error ? error.message : "Something went wrong.";
}
