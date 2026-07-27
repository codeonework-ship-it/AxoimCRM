# Documentation Drawer Master

The Axiom user-manual drawer is database-driven. Product guidance must not be added to `HelpDrawer.tsx`; that component is only a renderer for `GET /api/v1/documentation/drawer?locale={locale}`.

## Ownership and lifecycle

- `documentation.drawer_master` identifies the tenant's `USER_MANUAL` and its current version.
- `drawer_section` defines ordered layout blocks: callout, steps, shortcuts, or rule.
- `drawer_entry` defines the ordered content within a section.
- Translation tables hold English, German, Russian, and any later active locale without requiring a web release.
- `drawer_revision` stores an immutable complete snapshot for every runtime edit.
- Masters, translations, entries, sections, and revisions reject hard deletes. Operators inactivate sections or entries instead.

The tenant identifier participates in every relationship. PostgreSQL row-level security is enabled and forced on every documentation table.

## Administration and authorization

Administration → Documentation exposes the same master used by the drawer. Super administrators, tenant administrators, and data stewards may edit it. Super auditors and tenant auditors have read/export access. Every mutation creates an audit event and a transactional outbox event in the same transaction.

The public contract deliberately returns only active, localized content. If a requested translation is absent, English is used; if English is absent, the configuration is rejected rather than displaying invisible controls. The administration contract can include inactive records so content can be restored without recreating history.

## API

- `GET /api/v1/documentation/drawer?locale=en`
- `GET /api/v1/documentation/master?includeInactive=true`
- `PATCH /api/v1/documentation/master`
- `POST|PATCH /api/v1/documentation/master/sections[/{id}]`
- `POST|PATCH /api/v1/documentation/master/entries[/{id}]`

There are intentionally no delete endpoints.
