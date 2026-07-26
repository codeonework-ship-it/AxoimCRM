-- ============================================================================
-- V336 — UI theme catalogue and per-user theme preference
--
-- The five themes were a hardcoded TypeScript array (frontend ThemeSwitcher.tsx)
-- and the chosen theme lived in localStorage. Two consequences, both real:
--
--   1. The catalogue could not be governed. There was no way for an operator to
--      retire a theme, reorder the list, or set the tenant's opening theme
--      without a frontend deployment — and no record anywhere of what the
--      product offers, which reporting and support both need.
--   2. The preference did not follow the user. Signing in from a second machine,
--      a fresh browser profile, or after clearing site data silently reverted to
--      the default. A preference the product forgets is not a preference.
--
-- Both are fixed by moving the catalogue and the choice into the database.
--
-- WHY THE CATALOGUE IS GLOBAL AND READ-ONLY TO THE APP
-- A theme is product-level: its tokens ship in tokens.css and its skin in
-- motora.css, so a row here without matching CSS is a broken theme and a row in
-- CSS without a row here is an invisible one. Since only a deployment can add
-- the CSS half, only a migration should add the row half — the two must move
-- together. That is exactly the arrangement i18n.locale already uses (no RLS,
-- SELECT only to axiom_app), and this follows it rather than inventing a second
-- convention.
--
-- The PREFERENCE table is the writable half, is tenant-scoped, and carries RLS.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- The catalogue
-- ---------------------------------------------------------------------------
create table reference.ui_theme (
  code         text primary key,
  name         text not null,
  blurb        text not null,

  -- Three hex colours: ground, interactive accent, AI/provenance mark. Held as
  -- an array rather than three columns because it is a swatch — the UI renders
  -- it as a strip and never addresses one stop by name. The check pins the
  -- length and the format, so a malformed swatch cannot reach the client.
  swatch       text[] not null,

  -- Light or dark. The client needs this before it can decide what to paint
  -- during a pre-hydration flash, and support needs it to read a bug report.
  appearance   text not null check (appearance in ('LIGHT', 'DARK')),

  is_default   boolean not null default false,
  is_active    boolean not null default true,
  sort_order   integer not null default 0,
  created_at   timestamptz not null default now(),

  constraint ui_theme_code_format check (code ~ '^[a-z][a-z0-9_-]{1,30}$'),
  constraint ui_theme_swatch_shape check (
    array_length(swatch, 1) = 3
    and swatch[1] ~* '^#[0-9a-f]{6}$'
    and swatch[2] ~* '^#[0-9a-f]{6}$'
    and swatch[3] ~* '^#[0-9a-f]{6}$'
  )
);

comment on table reference.ui_theme is
  'Product-level catalogue of visual themes. Each row must have a matching
   :root[data-theme="<code>"] block in frontend/src/styles/tokens.css and a skin
   in motora.css; the CSS and the row ship in the same change.';

-- Exactly one default, enforced by the database rather than by hope. Same idiom
-- as uq_locale_single_default.
create unique index uq_ui_theme_single_default on reference.ui_theme(is_default)
  where is_default = true;

-- ---------------------------------------------------------------------------
-- Seed: the five themes that exist in CSS today.
--
-- Command Deck is the default because it is the product's identity — see the
-- header of tokens.css, "DARK IS THE PRODUCT". The two light editions sit
-- together in sort order so the picker presents them as the pair they are.
-- Every third swatch stop is the theme's gold/amber: GOLD IS RESERVED FOR AI
-- PROVENANCE across every theme, and the swatch shows it in that role.
-- ---------------------------------------------------------------------------
insert into reference.ui_theme (code, name, blurb, swatch, appearance, is_default, sort_order) values
  ('dark', 'Command Deck', 'Cinematic carbon and energon cyan',
   array['#05070b', '#35e0ff', '#ffb547'], 'DARK', true, 10),
  ('light', 'Arctic Frost', 'Glacial white, deep ice, frosted glass',
   array['#eef3f8', '#0b6e8f', '#8a5a00'], 'LIGHT', false, 20),
  ('meridian', 'Meridian', 'Warm stone, ocean ink, light from above',
   array['#e6e1d7', '#1a5fa0', '#8f6000'], 'LIGHT', false, 30),
  ('ironman', 'Mark VII', 'Hot-rod red, gold trim, arc-reactor glow',
   array['#1a1010', '#5fd3ee', '#f5b32a'], 'DARK', false, 40),
  ('tron', 'The Grid', 'True black, circuit traces, emissive cyan',
   array['#000000', '#6ff9ff', '#ff7a35'], 'DARK', false, 50);

-- ---------------------------------------------------------------------------
-- The per-user preference
-- ---------------------------------------------------------------------------
create table identity.user_ui_preference (
  tenant_id    uuid not null references platform.tenant(id),
  user_id      uuid not null,

  theme_code   text references reference.ui_theme(code),

  updated_at   timestamptz not null default now(),

  -- One row per user: this is a preference, not a history. Changes overwrite,
  -- and the audit trail of who changed their theme when lives in
  -- activity.user_activity, which records the request that did it.
  primary key (tenant_id, user_id),

  -- Tenant-composite FK, per ADR-001: a user_id alone could point at a user in
  -- another tenant, and the composite key makes that unrepresentable rather
  -- than merely discouraged.
  foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
    on delete cascade
);

comment on table identity.user_ui_preference is
  'Per-user UI preferences that must survive a change of browser. theme_code is
   nullable: null means "no explicit choice", which resolves to the catalogue
   default at read time rather than being written in — so changing the product
   default moves every user who never chose, which is the intent.';

comment on column identity.user_ui_preference.theme_code is
  'FK to reference.ui_theme. The FK is what makes a retired theme impossible to
   select and a deleted theme impossible to leave dangling.';

alter table identity.user_ui_preference enable row level security;
alter table identity.user_ui_preference force row level security;
create policy tenant_isolation on identity.user_ui_preference
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

grant select on reference.ui_theme to axiom_app;
grant select, insert, update, delete on identity.user_ui_preference to axiom_app;

-- The catalogue is deployment-governed: the app may read it and nothing more.
revoke insert, update, delete, truncate on reference.ui_theme from axiom_app;
