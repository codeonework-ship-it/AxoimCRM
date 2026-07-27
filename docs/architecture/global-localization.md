# Global localization architecture

## Scope

Axiom's product interface supports English (`en`), German (`de`) and Russian
(`ru`) before and after authentication. Locale selection applies to the login
experience, shell, lazy-loaded pages, forms, grids and tables, report studio,
PDF viewer controls, dialogs, toast notifications, audit/workflow drawers and
the in-product User Manual.

Customer names, record identifiers, notes, rich text, code samples and other
tenant data are not sent to a translation vendor. A subtree can opt out of UI
translation with `data-i18n-skip` or `translate="no"`.

## Resolution model

```text
tenant override for requested locale
  -> shipped translation for requested locale
  -> shipped English value
  -> inline English fallback
```

`I18nService` exposes two optionally authenticated read models:

- `GET /api/v1/i18n/bundle/{locale}` returns `key_path -> value` for explicit
  `t(key, fallback)` calls.
- `GET /api/v1/i18n/phrases/{locale}` returns `English phrase -> value` for the
  exact-phrase compatibility layer.

Anonymous requests receive shipped product vocabulary only. Authenticated
requests may also resolve the active tenant's terminology overrides under RLS.

## Frontend boundaries

New UI uses `useI18n()`:

- `t(key, fallback)` for stable product labels;
- `tp(source)` for a catalogue-controlled phrase supplied by a shared component
  or server definition;
- `format(key, fallback, values)` for named-token templates;
- `formatNumber` and `formatDate` for locale-correct presentation.

The exact-phrase observer covers legacy and lazy-rendered UI. It never performs
fuzzy or substring translation and retains the English source in a `WeakMap`,
so switching languages cannot chain German into Russian or corrupt React state.
It also translates accessible labels, titles, placeholders and alternative text.
Keyed and phrase bundles degrade independently during rolling deployments.

## Adding vocabulary

Add new keys and all shipped locale values in a forward-only Flyway migration.
Do not edit an applied migration. Product vocabulary belongs in
`i18n.translation_key` and `i18n.translation`; customer terminology belongs in
`i18n.tenant_translation_override`.

Every new shared control should use a key directly. The phrase layer exists to
give consistent coverage to legacy pages, not to replace semantic keys.

## Verification

- `I18nServiceTest` verifies locale validation, English fallback, anonymous
  isolation, tenant binding and deterministic phrase resolution.
- `internationalization.spec.ts` verifies pre-authentication switching and the
  authenticated navigation, reports, grids and User Manual in German and
  Russian.
- The full Playwright suite retains accessibility and runtime checks while a
  non-English locale is exercised.

