-- Axiom CRM — database-backed internationalisation registry (en / de / ru).
--
-- WHY THE DATABASE AND NOT A BUNDLE OF JSON FILES
-- Two requirements pull in the same direction:
--   1. The product ships in several languages and the string set grows with
--      every epic; shipping a frontend build per language is not viable.
--   2. A tenant must be able to relabel the vocabulary of their own CRM
--      ("Accounts" -> "Clients", "Leads" -> "Enquiries") without forking the
--      product. That is tenant data, so it belongs in a tenant-scoped table
--      under the same RLS regime as every other tenant row (ADR-001).
--
-- FOUR TABLES, TWO TENANCY CLASSES
--   i18n.locale                       platform reference  (no tenant_id, no RLS)
--   i18n.translation_key              platform reference  (no tenant_id, no RLS)
--   i18n.translation                  platform reference  (no tenant_id, no RLS)
--   i18n.tenant_translation_override  TENANT DATA         (tenant_id + FORCE RLS)
--
-- The first three are the product's own vocabulary: identical for every tenant,
-- shipped by us, versioned by migration. Giving them a tenant_id would mean
-- copying the whole string table per tenant on provisioning for no benefit.
-- Only the override table carries customer-authored content, and it is the only
-- one that gets row-level security.
--
-- RESOLUTION ORDER (implemented in com.axiom.i18n.I18nService)
--   tenant override for locale  ->  base translation for locale  ->  English
-- so a not-yet-translated German string degrades to English rather than
-- surfacing a raw key path in the UI.
--
-- This file is UTF-8. The German and Russian values below contain umlauts and
-- Cyrillic; do not re-save it in a single-byte encoding.

create schema if not exists i18n;

-- ---------------------------------------------------------------------------
-- Locales — platform reference data
-- ---------------------------------------------------------------------------
create table i18n.locale (
  code         text primary key,
  english_name text not null,
  native_name  text not null,
  is_default   boolean not null default false,
  active       boolean not null default true,
  sort_order   integer not null default 0,
  created_at   timestamptz not null default now(),
  constraint locale_code_format check (code ~ '^[a-z]{2}(-[A-Z]{2})?$')
);

-- Exactly one default locale, enforced by the database rather than by hope.
create unique index uq_locale_single_default on i18n.locale(is_default) where is_default = true;

-- ---------------------------------------------------------------------------
-- Translation keys — the catalogue of translatable strings
-- ---------------------------------------------------------------------------
create table i18n.translation_key (
  id          uuid primary key default gen_random_uuid(),
  key_path    text not null unique,
  description text,
  module_code text,
  created_at  timestamptz not null default now(),
  constraint translation_key_path_format check (key_path ~ '^[a-z][a-zA-Z0-9]*(\.[a-zA-Z0-9_-]+)+$')
);

-- ---------------------------------------------------------------------------
-- Base (product) translations — what we ship
-- ---------------------------------------------------------------------------
create table i18n.translation (
  id          uuid primary key default gen_random_uuid(),
  key_id      uuid not null references i18n.translation_key(id) on delete cascade,
  locale_code text not null references i18n.locale(code),
  value       text not null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (key_id, locale_code)
);

create index idx_translation_locale on i18n.translation(locale_code);

-- ---------------------------------------------------------------------------
-- Tenant overrides — customer-authored relabelling. TENANT DATA.
-- ---------------------------------------------------------------------------
create table i18n.tenant_translation_override (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  key_id      uuid not null references i18n.translation_key(id) on delete cascade,
  locale_code text not null references i18n.locale(code),
  value       text not null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (tenant_id, key_id, locale_code)
);

create index idx_tenant_translation_override_lookup
  on i18n.tenant_translation_override(tenant_id, locale_code);

alter table i18n.tenant_translation_override enable row level security;
alter table i18n.tenant_translation_override force row level security;

-- NOTE ON `nullif(..., '')` — a deliberate difference from the policy text used
-- on the V1/V6 tenant tables.
--
-- The bundle endpoint is reachable WITHOUT authentication (the login screen has
-- to be translated before anyone has a token), so this policy is the one place
-- in the schema that is evaluated on a connection with no tenant bound.
--
-- TenantSessionAspect sets app.tenant_id with set_config(..., is_local => true).
-- When that transaction ends, PostgreSQL restores the placeholder GUC to its
-- prior value, which for a never-session-set custom GUC is the EMPTY STRING and
-- not NULL. Verified on the dev database:
--
--   begin; select set_config('app.tenant_id','1111...',true); commit;
--   select current_setting('app.tenant_id', true)::uuid;
--   ERROR:  invalid input syntax for type uuid: ""
--
-- A bare `current_setting('app.tenant_id', true)::uuid` would therefore make
-- the anonymous bundle request fail with a cast error on any pooled connection
-- that had previously served an authenticated request — an intermittent 500
-- that depends on connection reuse. nullif() turns that into NULL, the
-- comparison yields NULL, and the row is filtered out. Zero override rows for
-- an anonymous caller is the correct and intended outcome: base translations
-- still resolve, so the login screen renders in the requested language.
create policy tenant_isolation on i18n.tenant_translation_override
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- Runtime grants and search_path
-- ---------------------------------------------------------------------------
grant usage on schema i18n to axiom_app;
grant select on i18n.locale, i18n.translation_key, i18n.translation to axiom_app;
grant select, insert, update, delete on i18n.tenant_translation_override to axiom_app;

alter role axiom_app set search_path to platform, identity, crm, sales, engagement, governance, reference, billing, reporting, integration, i18n, public;

-- ---------------------------------------------------------------------------
-- Module catalogue registration (governance.module_table_catalog, V6)
-- ---------------------------------------------------------------------------
insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('I18N', 'i18n', 'Internationalisation', 'Locales, translation keys, shipped translations and tenant relabelling overrides.', 'DATA_STEWARD');

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('i18n','locale','I18N','code',false,'PLATFORM'),
  ('i18n','translation_key','I18N','id',false,'PLATFORM'),
  ('i18n','translation','I18N','id',false,'PLATFORM'),
  ('i18n','tenant_translation_override','I18N','id',true,'ACTIVE');

-- ---------------------------------------------------------------------------
-- Seed: locales
-- ---------------------------------------------------------------------------
insert into i18n.locale(code, english_name, native_name, is_default, active, sort_order) values
  ('en', 'English', 'English',  true,  true, 10),
  ('de', 'German',  'Deutsch',  false, true, 20),
  ('ru', 'Russian', 'Русский',  false, true, 30);

-- ---------------------------------------------------------------------------
-- Seed: keys + translations for the navigation map and shell chrome.
--
-- One VALUES block per string keeps the English, German and Russian wording of
-- a given key on one screen, which is the only way a reviewer can actually
-- check a translation. The two inserts below fan it out into the normalised
-- tables.
-- ---------------------------------------------------------------------------
-- Dropped explicitly at the end of the file rather than with ON COMMIT DROP, so
-- the script behaves identically whether Flyway wraps it in a transaction or not.
create temporary table seed_i18n(
  key_path    text primary key,
  module_code text,
  description text,
  en text, de text, ru text
);

insert into seed_i18n(key_path, module_code, description, en, de, ru) values
  -- Navigation groups ------------------------------------------------------
  ('nav.group.workspace',    'SHELL', 'Sidebar group: personal daily workspace',
   'Workspace',        'Arbeitsbereich',            'Рабочая область'),
  ('nav.group.sell',         'SHELL', 'Sidebar group: core selling modules',
   'Sell',             'Verkauf',                   'Продажи'),
  ('nav.group.commerce',     'SHELL', 'Sidebar group: quote-to-cash modules',
   'Quote to cash',    'Angebot bis Zahlung',       'От предложения до оплаты'),
  ('nav.group.engage',       'SHELL', 'Sidebar group: marketing and service modules',
   'Engage & serve',   'Ansprechen & betreuen',     'Взаимодействие и сервис'),
  ('nav.group.intelligence', 'SHELL', 'Sidebar group: analytics and AI modules',
   'Intelligence',     'Analytik',                  'Аналитика'),
  ('nav.group.verticals',    'SHELL', 'Sidebar group: industry vertical packs',
   'Vertical packs',   'Branchenpakete',            'Отраслевые пакеты'),
  ('nav.group.platform',     'SHELL', 'Sidebar group: platform configuration modules',
   'Platform',         'Plattform',                 'Платформа'),
  ('nav.group.governance',   'SHELL', 'Sidebar group: administration, audit and compliance',
   'Governance',       'Governance',                'Управление и контроль'),

  -- Navigation modules -----------------------------------------------------
  ('nav.module.home',           'SHELL', 'Module: dashboard home',
   'Home',               'Start',                 'Главная'),
  ('nav.module.activities',     'SHELL', 'Module: activity and engagement timeline',
   'Activities',         'Aktivitäten',           'Активности'),
  ('nav.module.leads',          'SHELL', 'Module: lead capture and qualification',
   'Leads',              'Leads',                 'Лиды'),
  ('nav.module.pipeline',       'SHELL', 'Module: opportunity pipeline board',
   'Pipeline',           'Pipeline',              'Воронка продаж'),
  ('nav.module.accounts',       'SHELL', 'Module: company accounts master data',
   'Accounts',           'Kunden',                'Компании'),
  ('nav.module.forecast',       'SHELL', 'Module: revenue forecasting',
   'Forecast',           'Prognose',              'Прогноз'),
  ('nav.module.products',       'SHELL', 'Module: product and price book master data',
   'Products',           'Produkte',              'Продукты'),
  ('nav.module.quotes',         'SHELL', 'Module: quoting and configure-price-quote',
   'Quotes & CPQ',       'Angebote & CPQ',        'Предложения и CPQ'),
  ('nav.module.contracts',      'SHELL', 'Module: contract lifecycle',
   'Contracts',          'Verträge',              'Договоры'),
  ('nav.module.campaigns',      'SHELL', 'Module: marketing campaigns',
   'Campaigns',          'Kampagnen',             'Кампании'),
  ('nav.module.cases',          'SHELL', 'Module: customer service cases',
   'Cases',              'Tickets',               'Обращения'),
  ('nav.module.partners',       'SHELL', 'Module: partner and channel management',
   'Partners',           'Partner',               'Партнёры'),
  ('nav.module.reports',        'SHELL', 'Module: reporting and analytics',
   'Reports',            'Berichte',              'Отчёты'),
  ('nav.module.copilot',        'SHELL', 'Module: AI assistant',
   'AI Copilot',         'KI-Copilot',            'ИИ-помощник'),
  ('nav.module.bfsi',           'SHELL', 'Module: banking, financial services and insurance pack',
   'BFSI',               'BFSI',                  'BFSI'),
  ('nav.module.commodity',      'SHELL', 'Module: commodity trading pack',
   'Commodity',          'Rohstoffe',             'Сырьевые товары'),
  ('nav.module.referenceData',  'SHELL', 'Module: governed reference data and value sets',
   'Reference Data',     'Referenzdaten',         'Справочные данные'),
  ('nav.module.automation',     'SHELL', 'Module: workflow automation',
   'Automation',         'Automatisierung',       'Автоматизация'),
  ('nav.module.integrations',   'SHELL', 'Module: external system integrations',
   'Integrations',       'Integrationen',         'Интеграции'),
  ('nav.module.migration',      'SHELL', 'Module: data migration tooling',
   'Migration',          'Migration',             'Миграция'),
  ('nav.module.mobile',         'SHELL', 'Module: mobile client configuration',
   'Mobile',             'Mobil',                 'Мобильный доступ'),
  ('nav.module.administration', 'SHELL', 'Module: tenant and platform administration',
   'Administration',     'Administration',        'Администрирование'),
  ('nav.module.audit',          'SHELL', 'Module: audit trail and compliance',
   'Audit & Compliance', 'Audit & Compliance',    'Аудит и комплаенс'),

  -- Navigation badges ------------------------------------------------------
  ('nav.badge.planned', 'SHELL', 'Badge on a specified but not yet built module',
   'Planned', 'Geplant', 'Запланировано'),
  ('nav.badge.beta',    'SHELL', 'Badge on a partially implemented module',
   'Beta',    'Beta',    'Бета'),
  ('nav.badge.plannedTitle', 'SHELL', 'Tooltip on a planned module entry',
   'planned, not yet built', 'geplant, noch nicht umgesetzt', 'запланировано, ещё не реализовано'),
  ('nav.badge.betaTitle',    'SHELL', 'Tooltip on a partially implemented module entry',
   'Partially implemented', 'Teilweise umgesetzt', 'Реализовано частично'),

  -- Shell chrome -----------------------------------------------------------
  ('shell.signOut',       'SHELL', 'Sidebar control that ends the session',
   'Sign out',                    'Abmelden',                            'Выйти'),
  ('shell.toggleSidebar', 'SHELL', 'Top bar control that shows or hides the navigation rail',
   'Toggle sidebar',              'Seitenleiste ein-/ausblenden',        'Показать или скрыть боковую панель'),
  ('shell.search',        'SHELL', 'Top bar search and command launcher',
   'Search or run a command',     'Suchen oder Befehl ausführen',        'Поиск или выполнение команды'),
  ('shell.notifications', 'SHELL', 'Top bar notification centre',
   'Notifications',               'Benachrichtigungen',                  'Уведомления'),
  ('shell.unread',        'SHELL', 'Suffix in the notification count announced to screen readers',
   'unread',                      'ungelesen',                           'непрочитанных'),
  ('shell.manual',        'SHELL', 'Top bar control that opens the user manual',
   'User Manual',                 'Benutzerhandbuch',                    'Руководство пользователя'),
  ('shell.theme',         'SHELL', 'Top bar control that switches light and dark theme',
   'Toggle theme',                'Design wechseln',                     'Переключить тему'),
  ('shell.language',      'SHELL', 'Micro-label above the language selector',
   'Language',                    'Sprache',                             'Язык');

insert into i18n.translation_key(key_path, description, module_code)
select key_path, description, module_code from seed_i18n;

insert into i18n.translation(key_id, locale_code, value)
select k.id, v.locale_code, v.value
from seed_i18n s
join i18n.translation_key k on k.key_path = s.key_path
cross join lateral (values ('en', s.en), ('de', s.de), ('ru', s.ru)) as v(locale_code, value);

drop table seed_i18n;
