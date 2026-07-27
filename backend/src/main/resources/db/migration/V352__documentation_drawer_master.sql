-- Governed, tenant-scoped documentation drawer master.
-- Content is normalized and localized; masters cannot be hard-deleted.

create schema if not exists documentation;

create table documentation.drawer_master (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  current_version integer not null default 1 check (current_version > 0),
  active boolean not null default true,
  created_by uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint drawer_master_code_format check (code ~ '^[A-Z][A-Z0-9_]{2,79}$')
);

create table documentation.drawer_translation (
  tenant_id uuid not null,
  drawer_id uuid not null,
  locale_code text not null references i18n.locale(code),
  eyebrow text not null check (length(eyebrow) between 1 and 120),
  title text not null check (length(title) between 1 and 180),
  primary key (tenant_id, drawer_id, locale_code),
  foreign key (tenant_id, drawer_id) references documentation.drawer_master(tenant_id, id)
);

create table documentation.drawer_section (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null,
  drawer_id uuid not null,
  code text not null,
  section_type text not null check (section_type in ('CALLOUT','STEPS','SHORTCUTS','RULE')),
  sort_order integer not null check (sort_order between 1 and 10000),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, drawer_id, code),
  unique (tenant_id, drawer_id, sort_order),
  unique (tenant_id, id),
  foreign key (tenant_id, drawer_id) references documentation.drawer_master(tenant_id, id),
  constraint drawer_section_code_format check (code ~ '^[A-Z][A-Z0-9_]{2,79}$')
);

create table documentation.drawer_section_translation (
  tenant_id uuid not null,
  section_id uuid not null,
  locale_code text not null references i18n.locale(code),
  heading text check (heading is null or length(heading) between 1 and 180),
  primary key (tenant_id, section_id, locale_code),
  foreign key (tenant_id, section_id) references documentation.drawer_section(tenant_id, id)
);

create table documentation.drawer_entry (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null,
  section_id uuid not null,
  code text not null,
  marker text check (marker is null or length(marker) between 1 and 30),
  sort_order integer not null check (sort_order between 1 and 10000),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, section_id, code),
  unique (tenant_id, section_id, sort_order),
  unique (tenant_id, id),
  foreign key (tenant_id, section_id) references documentation.drawer_section(tenant_id, id),
  constraint drawer_entry_code_format check (code ~ '^[A-Z][A-Z0-9_]{2,79}$')
);

create table documentation.drawer_entry_translation (
  tenant_id uuid not null,
  entry_id uuid not null,
  locale_code text not null references i18n.locale(code),
  title text not null check (length(title) between 1 and 240),
  body text check (body is null or length(body) between 1 and 4000),
  primary key (tenant_id, entry_id, locale_code),
  foreign key (tenant_id, entry_id) references documentation.drawer_entry(tenant_id, id)
);

create table documentation.drawer_revision (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null,
  drawer_id uuid not null,
  version_no integer not null check (version_no > 0),
  snapshot jsonb not null,
  change_note text not null check (length(change_note) between 3 and 500),
  created_by uuid,
  created_at timestamptz not null default now(),
  unique (tenant_id, drawer_id, version_no),
  foreign key (tenant_id, drawer_id) references documentation.drawer_master(tenant_id, id)
);

create index idx_drawer_section_order on documentation.drawer_section(tenant_id, drawer_id, sort_order) where active;
create index idx_drawer_entry_order on documentation.drawer_entry(tenant_id, section_id, sort_order) where active;

do $$ declare table_name text; begin
  foreach table_name in array array['drawer_master','drawer_translation','drawer_section',
    'drawer_section_translation','drawer_entry','drawer_entry_translation','drawer_revision'] loop
    execute format('alter table documentation.%I enable row level security', table_name);
    execute format('alter table documentation.%I force row level security', table_name);
    execute format('create policy tenant_isolation on documentation.%I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name);
  end loop;
end $$;

create function documentation.reject_master_delete() returns trigger language plpgsql as $$
begin
  raise exception 'Documentation masters cannot be hard-deleted; set active=false instead';
end $$;

create trigger no_delete_drawer before delete on documentation.drawer_master for each row execute function documentation.reject_master_delete();
create trigger no_delete_section before delete on documentation.drawer_section for each row execute function documentation.reject_master_delete();
create trigger no_delete_entry before delete on documentation.drawer_entry for each row execute function documentation.reject_master_delete();

grant usage on schema documentation to axiom_app;
grant select, insert, update on all tables in schema documentation to axiom_app;

insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role)
select 'DOCUMENTATION', 'documentation', 'Documentation', 'Versioned multilingual documentation drawer masters.', 'DATA_STEWARD'
where not exists (select 1 from governance.module_catalog where module_code = 'DOCUMENTATION');

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle)
select value.schema_name, value.table_name, 'DOCUMENTATION', value.primary_key, true, value.lifecycle
from (values
  ('documentation','drawer_master','id','SOFT_DELETE'),
  ('documentation','drawer_translation','drawer_id,locale_code','ACTIVE'),
  ('documentation','drawer_section','id','SOFT_DELETE'),
  ('documentation','drawer_section_translation','section_id,locale_code','ACTIVE'),
  ('documentation','drawer_entry','id','SOFT_DELETE'),
  ('documentation','drawer_entry_translation','entry_id,locale_code','ACTIVE'),
  ('documentation','drawer_revision','id','APPEND_ONLY')
) value(schema_name, table_name, primary_key, lifecycle)
where not exists (
  select 1 from governance.module_table_catalog c
  where c.schema_name = value.schema_name and c.table_name = value.table_name
);

insert into documentation.drawer_master(tenant_id, code)
select id, 'USER_MANUAL' from platform.tenant
on conflict (tenant_id, code) do nothing;

insert into documentation.drawer_translation(tenant_id, drawer_id, locale_code, eyebrow, title)
select d.tenant_id, d.id, value.locale_code, value.eyebrow, value.title
from documentation.drawer_master d
cross join (values
  ('en','Field manual · 01','User Manual'),
  ('de','Feldhandbuch · 01','Benutzerhandbuch'),
  ('ru','Полевое руководство · 01','Руководство пользователя')
) value(locale_code, eyebrow, title)
where d.code = 'USER_MANUAL'
on conflict do nothing;

create temporary table seed_doc_section(code text primary key, section_type text, sort_order integer,
  heading_en text, heading_de text, heading_ru text);
insert into seed_doc_section values
  ('FASTEST_ROUTE','CALLOUT',10,null,null,null),
  ('CORE_LOOP','STEPS',20,'Core loop','Kernablauf','Основной цикл'),
  ('ADMIN_MODULES','STEPS',30,'Admin modules','Administrationsmodule','Модули администрирования'),
  ('KEYBOARD_MAP','SHORTCUTS',40,'Keyboard map','Tastaturübersicht','Карта клавиш'),
  ('AI_PROVENANCE','RULE',50,null,null,null);

insert into documentation.drawer_section(tenant_id, drawer_id, code, section_type, sort_order)
select d.tenant_id, d.id, s.code, s.section_type, s.sort_order
from documentation.drawer_master d cross join seed_doc_section s
where d.code = 'USER_MANUAL'
on conflict (tenant_id, drawer_id, code) do nothing;

insert into documentation.drawer_section_translation(tenant_id, section_id, locale_code, heading)
select s.tenant_id, s.id, value.locale_code, value.heading
from documentation.drawer_section s
join seed_doc_section seed on seed.code = s.code
cross join lateral (values ('en',seed.heading_en),('de',seed.heading_de),('ru',seed.heading_ru)) value(locale_code,heading)
on conflict do nothing;

create temporary table seed_doc_entry(section_code text, code text, marker text, sort_order integer,
  title_en text, title_de text, title_ru text, body_en text, body_de text, body_ru text,
  primary key(section_code, code));
insert into seed_doc_entry values
('FASTEST_ROUTE','QUICK_START',null,10,'Your fastest route','Ihr schnellster Weg','Ваш самый быстрый путь',
 'Start on Home, resolve flagged deals, then work the pipeline from left to right.',
 'Beginnen Sie auf der Startseite, klären Sie markierte Verkaufschancen und bearbeiten Sie dann die Pipeline von links nach rechts.',
 'Начните с главной страницы, устраните отмеченные проблемы и затем проходите воронку слева направо.'),
('CORE_LOOP','SCAN_HOME','01',10,'Scan Home','Startseite prüfen','Проверить главную страницу',
 'Review revenue posture and intervention signals.','Prüfen Sie Umsatzlage und Eingriffssignale.','Проверьте состояние выручки и сигналы вмешательства.'),
('CORE_LOOP','QUALIFY_LEADS','02',20,'Qualify leads','Leads qualifizieren','Квалифицировать лиды',
 'Convert qualified demand into an account, contact, and deal.','Wandeln Sie qualifizierte Nachfrage in Kunde, Kontakt und Verkaufschance um.','Преобразуйте квалифицированный спрос в компанию, контакт и сделку.'),
('CORE_LOOP','ADVANCE_DEALS','03',30,'Advance deals','Verkaufschancen voranbringen','Продвигать сделки',
 'Drag cards only after stage requirements are satisfied.','Verschieben Sie Karten erst, wenn die Phasenanforderungen erfüllt sind.','Перемещайте карточки только после выполнения требований этапа.'),
('CORE_LOOP','CAPTURE_ENGAGEMENT','04',40,'Capture engagement','Interaktionen erfassen','Фиксировать взаимодействия',
 'Use Activities to log tasks, events, calls, notes and manual email summaries against CRM records.','Erfassen Sie unter Aktivitäten Aufgaben, Termine, Anrufe, Notizen und manuelle E-Mail-Zusammenfassungen zu CRM-Datensätzen.','Используйте раздел «Активности» для регистрации задач, событий, звонков, заметок и сводок писем по записям CRM.'),
('ADMIN_MODULES','RBAC_FIRST','05',10,'RBAC first','Zuerst RBAC','Сначала RBAC',
 'Review role policies before changing users, trials, company status, billing or alerts.','Prüfen Sie Rollenrichtlinien, bevor Sie Benutzer, Testzugänge, Unternehmensstatus, Abrechnung oder Warnungen ändern.','Проверяйте ролевые политики перед изменением пользователей, пробных доступов, статуса компании, биллинга или оповещений.'),
('ADMIN_MODULES','REPORTS','06',20,'Reports','Berichte','Отчеты',
 'Use Reports for governed PDF, Excel and Word downloads for the selected workspace.','Verwenden Sie Berichte für kontrollierte PDF-, Excel- und Word-Downloads des gewählten Arbeitsbereichs.','Используйте отчеты для управляемой загрузки PDF, Excel и Word в выбранной рабочей области.'),
('ADMIN_MODULES','ALERT_QUEUES','07',30,'Alert queues','Warnungswarteschlangen','Очереди оповещений',
 'Email and report alerts are validated and queued internally until third-party delivery is connected.','E-Mail- und Berichtswarnungen werden validiert und intern eingereiht, bis ein externer Versand angebunden ist.','Почтовые и отчетные оповещения проверяются и ставятся во внутреннюю очередь до подключения внешней доставки.'),
('KEYBOARD_MAP','COMMAND_CENTER','Ctrl K',10,'Open command center','Befehlszentrale öffnen','Открыть центр команд',null,null,null),
('KEYBOARD_MAP','GO_HOME','G then H',20,'Go to Home','Zur Startseite','Перейти на главную',null,null,null),
('KEYBOARD_MAP','GO_PIPELINE','G then P',30,'Go to Pipeline','Zur Pipeline','Перейти к воронке',null,null,null),
('KEYBOARD_MAP','GO_ACCOUNTS','G then A',40,'Go to Accounts','Zu Kunden','Перейти к компаниям',null,null,null),
('KEYBOARD_MAP','GO_LEADS','G then L',50,'Go to Leads','Zu Leads','Перейти к лидам',null,null,null),
('KEYBOARD_MAP','GO_ACTIVITIES','G then E',60,'Go to Activities','Zu Aktivitäten','Перейти к активностям',null,null,null),
('KEYBOARD_MAP','GO_REFERENCE','G then R',70,'Go to Reference Data','Zu Referenzdaten','Перейти к справочным данным',null,null,null),
('KEYBOARD_MAP','GO_REPORTS','G then T',80,'Go to Reports','Zu Berichten','Перейти к отчетам',null,null,null),
('KEYBOARD_MAP','GO_ADMIN','G then U',90,'Go to Administration','Zur Administration','Перейти к администрированию',null,null,null),
('KEYBOARD_MAP','OPEN_GUIDE','Ctrl /',100,'Open this guide','Dieses Handbuch öffnen','Открыть это руководство',null,null,null),
('AI_PROVENANCE','AI_RULE','AI',10,'Gold always means machine-generated.','Gold kennzeichnet immer maschinell erzeugte Inhalte.','Золотой цвет всегда означает машинное создание.',
 'Review it before acting; customer data and system status never use gold.','Prüfen Sie solche Inhalte vor dem Handeln; Kundendaten und Systemstatus verwenden niemals Gold.','Проверяйте такие материалы перед действием; данные клиентов и состояние системы никогда не обозначаются золотым.');

insert into documentation.drawer_entry(tenant_id, section_id, code, marker, sort_order)
select s.tenant_id, s.id, e.code, e.marker, e.sort_order
from documentation.drawer_section s join seed_doc_entry e on e.section_code = s.code
on conflict (tenant_id, section_id, code) do nothing;

insert into documentation.drawer_entry_translation(tenant_id, entry_id, locale_code, title, body)
select e.tenant_id, e.id, value.locale_code, value.title, value.body
from documentation.drawer_entry e
join documentation.drawer_section s on s.tenant_id = e.tenant_id and s.id = e.section_id
join seed_doc_entry seed on seed.section_code = s.code and seed.code = e.code
cross join lateral (values
 ('en',seed.title_en,seed.body_en),('de',seed.title_de,seed.body_de),('ru',seed.title_ru,seed.body_ru)
) value(locale_code,title,body)
on conflict do nothing;

insert into documentation.drawer_revision(tenant_id, drawer_id, version_no, snapshot, change_note)
select tenant_id, id, 1, jsonb_build_object('seeded', true, 'code', code), 'Initial governed user manual seed'
from documentation.drawer_master
on conflict do nothing;

drop table seed_doc_entry;
drop table seed_doc_section;
