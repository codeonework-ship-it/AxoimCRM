import { useMemo, useRef, useState, type KeyboardEvent } from "react";
import type { RoleNode } from "../api/rbac";

/**
 * The role hierarchy as a real tree, with create / rename / reparent / delete
 * in place.
 *
 * <h2>Why a tree and not a table</h2>
 * The hierarchy IS the data. A flat list of roles with a "parent" column tells
 * an administrator nothing about the shape they are editing, and the shape is
 * the whole point — access rolls upward through it, siblings are invisible to
 * each other, and a re-parent moves a subtree. You cannot reason about that
 * from rows. (There is also a filterable, groupable table of the same roles on
 * this page: the tree is for shape, the table is for lookup.)
 *
 * <h2>Cycle rejection lands on the node</h2>
 * FR-SEC-001 requires an attempted cycle to be "rejected naming the conflicting
 * roles". The server does name them; this component pins that message to the
 * node the administrator was editing rather than firing a toast, because a toast
 * disappears while the half-finished re-parent is still on screen and leaves the
 * user with no idea which of forty nodes was refused.
 *
 * <h2>Keyboard</h2>
 * `role="tree"` with roving tabindex. Arrow up/down walk the visible nodes,
 * right expands (then descends), left collapses (then ascends), Home/End jump.
 * Re-parenting is a select control, never drag-only: a drag-only move is
 * unusable without a mouse and this is an administration surface where the
 * keyboard user is often the auditor.
 */

export interface RoleTreeProps {
  roles: RoleNode[];
  canWrite: boolean;
  /** Set while any mutation is in flight, so controls can disable. */
  busy: boolean;
  /** Per-node error, keyed by role id — where a rejected cycle is shown. */
  errors: Record<string, string>;
  onCreateChild: (parent: RoleNode | null, code: string, name: string) => void;
  onRename: (role: RoleNode, name: string) => void;
  onReparent: (role: RoleNode, parentId: string | null) => void;
  onDelete: (role: RoleNode) => void;
  onDismissError: (roleId: string) => void;
}

interface TreeRow {
  role: RoleNode;
  depth: number;
  hasChildren: boolean;
}

const ROOT = "__root__";

export function RoleTree({
  roles,
  canWrite,
  busy,
  errors,
  onCreateChild,
  onRename,
  onReparent,
  onDelete,
  onDismissError,
}: RoleTreeProps) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [focused, setFocused] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [draftName, setDraftName] = useState("");
  const [addingUnder, setAddingUnder] = useState<string | null>(null);
  const [draftCode, setDraftCode] = useState("");
  const [draftChildName, setDraftChildName] = useState("");
  const nodeRefs = useRef(new Map<string, HTMLDivElement>());

  const childrenOf = useMemo(() => {
    const map = new Map<string, RoleNode[]>();
    roles.forEach((role) => {
      const key = role.parentId ?? ROOT;
      const bucket = map.get(key);
      if (bucket) bucket.push(role);
      else map.set(key, [role]);
    });
    map.forEach((bucket) => bucket.sort((a, b) => a.code.localeCompare(b.code)));
    return map;
  }, [roles]);

  /** Flattened visible rows — the sequence arrow keys actually traverse. */
  const visible = useMemo(() => {
    const out: TreeRow[] = [];
    const walk = (parentKey: string, depth: number) => {
      (childrenOf.get(parentKey) ?? []).forEach((role) => {
        const kids = childrenOf.get(role.id) ?? [];
        out.push({ role, depth, hasChildren: kids.length > 0 });
        if (kids.length > 0 && !collapsed.has(role.id)) walk(role.id, depth + 1);
      });
    };
    walk(ROOT, 0);
    return out;
  }, [childrenOf, collapsed]);

  const activeId = focused ?? visible[0]?.role.id ?? null;

  function focusNode(id: string) {
    setFocused(id);
    nodeRefs.current.get(id)?.focus();
  }

  function toggle(id: string, expand?: boolean) {
    setCollapsed((current) => {
      const next = new Set(current);
      const shouldExpand = expand ?? next.has(id);
      if (shouldExpand) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>, row: TreeRow) {
    const index = visible.findIndex((candidate) => candidate.role.id === row.role.id);
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        if (index < visible.length - 1) focusNode(visible[index + 1].role.id);
        break;
      case "ArrowUp":
        event.preventDefault();
        if (index > 0) focusNode(visible[index - 1].role.id);
        break;
      case "ArrowRight":
        event.preventDefault();
        if (row.hasChildren && collapsed.has(row.role.id)) toggle(row.role.id, true);
        else if (row.hasChildren && index < visible.length - 1) focusNode(visible[index + 1].role.id);
        break;
      case "ArrowLeft":
        event.preventDefault();
        if (row.hasChildren && !collapsed.has(row.role.id)) toggle(row.role.id, false);
        else if (row.role.parentId) focusNode(row.role.parentId);
        break;
      case "Home":
        event.preventDefault();
        if (visible.length > 0) focusNode(visible[0].role.id);
        break;
      case "End":
        event.preventDefault();
        if (visible.length > 0) focusNode(visible[visible.length - 1].role.id);
        break;
      default:
        break;
    }
  }

  /**
   * Candidate parents for a move. The node's own subtree is excluded here so the
   * obvious cycle is not even offered — but the server still rejects it, because
   * a UI that only hides an illegal option has not enforced anything.
   */
  function parentOptions(role: RoleNode): RoleNode[] {
    return roles.filter(
      (candidate) => candidate.id !== role.id && !candidate.path.startsWith(role.path),
    );
  }

  function startAdd(parentId: string | null) {
    setAddingUnder(parentId ?? ROOT);
    setDraftCode("");
    setDraftChildName("");
  }

  function submitAdd(parent: RoleNode | null) {
    if (!draftCode.trim() || !draftChildName.trim()) return;
    onCreateChild(parent, draftCode.trim().toUpperCase(), draftChildName.trim());
    setAddingUnder(null);
  }

  function deletionBlocker(row: TreeRow): string | null {
    if (row.hasChildren) {
      return "This role has child roles. Move or remove them first — deleting a branch would "
        + "silently detach everyone beneath it.";
    }
    if (row.role.memberCount > 0) {
      return `This role still has ${row.role.memberCount} member${row.role.memberCount === 1 ? "" : "s"}. `
        + "Reassign them first, or they would lose their place in the hierarchy without anyone deciding to.";
    }
    return null;
  }

  return (
    <div>
      <div className="page-controls">
        <div>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => setCollapsed(new Set())}
            disabled={collapsed.size === 0}
          >
            Expand all
          </button>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => setCollapsed(new Set(roles.filter((r) => r.depth === 0).map((r) => r.id)))}
          >
            Collapse all
          </button>
          {canWrite && (
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={() => startAdd(null)}
              disabled={busy}
            >
              New root role
            </button>
          )}
        </div>
        <span className="count">{roles.length} roles</span>
      </div>

      {addingUnder === ROOT && (
        <NewRoleForm
          label="New root role"
          code={draftCode}
          name={draftChildName}
          onCode={setDraftCode}
          onName={setDraftChildName}
          onCancel={() => setAddingUnder(null)}
          onSubmit={() => submitAdd(null)}
          busy={busy}
        />
      )}

      <div className="panel">
        <ul role="tree" aria-label="Role hierarchy" style={{ listStyle: "none", margin: 0, padding: 8 }}>
          {visible.length === 0 && <li className="empty-note">No roles are defined for this workspace.</li>}
          {visible.map((row) => {
            const { role } = row;
            const expanded = row.hasChildren ? !collapsed.has(role.id) : undefined;
            const blocker = deletionBlocker(row);
            const error = errors[role.id];
            return (
              <li key={role.id} role="none" style={{ margin: 0 }}>
                <div
                  role="treeitem"
                  aria-level={row.depth + 1}
                  aria-expanded={expanded}
                  aria-selected={activeId === role.id}
                  tabIndex={activeId === role.id ? 0 : -1}
                  ref={(node) => {
                    if (node) nodeRefs.current.set(role.id, node);
                    else nodeRefs.current.delete(role.id);
                  }}
                  onKeyDown={(event) => onKeyDown(event, row)}
                  onFocus={() => setFocused(role.id)}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    flexWrap: "wrap",
                    gap: 8,
                    padding: "6px 8px",
                    paddingLeft: 8 + row.depth * 22,
                    borderBottom: "1px solid var(--line)",
                  }}
                >
                  {row.hasChildren ? (
                    <button
                      type="button"
                      className="icon-btn"
                      aria-label={`${expanded ? "Collapse" : "Expand"} ${role.code}`}
                      tabIndex={-1}
                      onClick={() => toggle(role.id)}
                    >
                      {expanded ? "-" : "+"}
                    </button>
                  ) : (
                    <span className="sr-only">Leaf role</span>
                  )}

                  {editing === role.id ? (
                    <>
                      <input
                        aria-label={`Rename ${role.code}`}
                        value={draftName}
                        onChange={(event) => setDraftName(event.target.value)}
                      />
                      <button
                        type="button"
                        className="btn btn-sm btn-primary"
                        disabled={busy || !draftName.trim()}
                        onClick={() => {
                          onRename(role, draftName.trim());
                          setEditing(null);
                        }}
                      >
                        Save
                      </button>
                      <button type="button" className="link-btn" onClick={() => setEditing(null)}>
                        Cancel
                      </button>
                    </>
                  ) : (
                    <>
                      <strong className="mono">{role.code}</strong>
                      <span>{role.name}</span>
                      <span className="chip">{role.memberCount} members</span>
                      {!role.active && <span className="chip">Inactive</span>}
                    </>
                  )}

                  {canWrite && editing !== role.id && (
                    <>
                      <button
                        type="button"
                        className="link-btn"
                        tabIndex={-1}
                        disabled={busy}
                        onClick={() => startAdd(role.id)}
                      >
                        Add child
                      </button>
                      <button
                        type="button"
                        className="link-btn"
                        tabIndex={-1}
                        disabled={busy}
                        onClick={() => {
                          setEditing(role.id);
                          setDraftName(role.name);
                        }}
                      >
                        Rename
                      </button>
                      <label className="sr-only" htmlFor={`move-${role.id}`}>
                        Move {role.code} to a new parent
                      </label>
                      <select
                        id={`move-${role.id}`}
                        value={role.parentId ?? ""}
                        disabled={busy}
                        onChange={(event) => onReparent(role, event.target.value || null)}
                      >
                        <option value="">(top level)</option>
                        {parentOptions(role).map((candidate) => (
                          <option key={candidate.id} value={candidate.id}>
                            Move under {candidate.code}
                          </option>
                        ))}
                      </select>
                      <button
                        type="button"
                        className="link-btn danger-link"
                        tabIndex={-1}
                        disabled={busy || blocker !== null}
                        title={blocker ?? "Deactivate this role"}
                        onClick={() => onDelete(role)}
                      >
                        Delete
                      </button>
                    </>
                  )}
                </div>

                {blocker && canWrite && (
                  <p className="loading-note" style={{ paddingLeft: 30 + row.depth * 22 }}>
                    {blocker}
                  </p>
                )}

                {error && (
                  <div className="form-error" style={{ marginLeft: 8 + row.depth * 22 }}>
                    <strong>Change refused.</strong> {error}{" "}
                    <button type="button" className="link-btn" onClick={() => onDismissError(role.id)}>
                      Dismiss
                    </button>
                  </div>
                )}

                {addingUnder === role.id && (
                  <div style={{ paddingLeft: 30 + row.depth * 22 }}>
                    <NewRoleForm
                      label={`New role under ${role.code}`}
                      code={draftCode}
                      name={draftChildName}
                      onCode={setDraftCode}
                      onName={setDraftChildName}
                      onCancel={() => setAddingUnder(null)}
                      onSubmit={() => submitAdd(role)}
                      busy={busy}
                    />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}

function NewRoleForm({
  label,
  code,
  name,
  onCode,
  onName,
  onCancel,
  onSubmit,
  busy,
}: {
  label: string;
  code: string;
  name: string;
  onCode: (value: string) => void;
  onName: (value: string) => void;
  onCancel: () => void;
  onSubmit: () => void;
  busy: boolean;
}) {
  return (
    <div className="panel" style={{ padding: 12, display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center" }}>
      <span className="label">{label}</span>
      <input
        aria-label="Role code"
        placeholder="APAC_NORTH"
        value={code}
        onChange={(event) => onCode(event.target.value.toUpperCase())}
      />
      <input
        aria-label="Role name"
        placeholder="Field team — North"
        value={name}
        onChange={(event) => onName(event.target.value)}
      />
      <button
        type="button"
        className="btn btn-sm btn-primary"
        disabled={busy || !code.trim() || !name.trim()}
        onClick={onSubmit}
      >
        Create
      </button>
      <button type="button" className="link-btn" onClick={onCancel}>
        Cancel
      </button>
    </div>
  );
}
