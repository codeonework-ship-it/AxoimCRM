\set ON_ERROR_STOP on

-- Axiom CRM logical-million scale fixture.
--
-- This fixture deliberately stores the ordinal once and exposes one million
-- deterministic rows for every transactional screen through a cross-joined
-- view.  It exercises the database pagination/search/filter contract without
-- copying tens of millions of throw-away business records into the developer
-- database.  It is QA evidence, never product or demonstration data.

create schema if not exists qa;

create unlogged table if not exists qa.scale_ordinal (
    ordinal integer primary key,
    bucket smallint generated always as ((ordinal - 1) % 20) stored,
    amount numeric(18,2) generated always as ((ordinal * 17)::numeric / 100) stored
);

insert into qa.scale_ordinal(ordinal)
select value
from generate_series(1, 1000000) value
on conflict do nothing;

create table if not exists qa.screen_scale_target (
    screen_code text primary key,
    route text not null unique,
    display_name text not null,
    module_code text not null,
    data_class text not null check (data_class in ('TRANSACTION', 'MASTER')),
    target_rows integer not null default 1000000 check (target_rows > 0),
    refreshed_at timestamptz not null default now()
);

insert into qa.screen_scale_target(screen_code, route, display_name, module_code, data_class)
select screen_code, route, display_name, module_code,
       case
         when module_code in ('REFERENCE', 'ORGDATA', 'IDENTITY', 'GOVERNANCE')
           or route like '/reference-data%'
           or route in ('/products', '/price-books', '/admin/users', '/admin/rbac',
                        '/admin/documentation', '/engagement/templates')
         then 'MASTER'
         else 'TRANSACTION'
       end
from governance.screen_catalog
where active
on conflict (screen_code) do update
set route = excluded.route,
    display_name = excluded.display_name,
    module_code = excluded.module_code,
    data_class = excluded.data_class,
    refreshed_at = now();

create or replace view qa.transaction_screen_dataset as
select target.screen_code,
       target.route,
       ordinal.ordinal as synthetic_id,
       'QA-' || target.screen_code || '-' || lpad(ordinal.ordinal::text, 7, '0') as record_key,
       'Synthetic ' || target.display_name || ' ' || ordinal.ordinal as display_value,
       ordinal.bucket as status_bucket,
       ordinal.amount,
       timestamp '2024-01-01 00:00:00'
         + ((ordinal.ordinal % 1051200) * interval '1 minute') as occurred_at
from qa.screen_scale_target target
cross join qa.scale_ordinal ordinal
where target.data_class = 'TRANSACTION';

create table if not exists qa.master_edge_case_catalog (
    case_code text primary key,
    category text not null,
    value_sample text,
    expected_result text not null
);

insert into qa.master_edge_case_catalog(case_code, category, value_sample, expected_result) values
 ('MASTER_EMPTY_REQUIRED', 'VALIDATION', '', 'Rejected with the required field named'),
 ('MASTER_MAX_LENGTH', 'BOUNDARY', repeat('M', 160), 'Accepted at the documented maximum'),
 ('MASTER_OVER_LENGTH', 'BOUNDARY', repeat('X', 161), 'Rejected without partial writes'),
 ('MASTER_UNICODE', 'LOCALIZATION', '東京 Équipe الرياض', 'Round-trips without corruption'),
 ('MASTER_CSV_FORMULA', 'SECURITY', '=HYPERLINK("https://invalid")', 'Export neutralizes spreadsheet formula execution'),
 ('MASTER_DUPLICATE_FILE', 'DUPLICATE', 'same value twice', 'Duplicate row is identified'),
 ('MASTER_DUPLICATE_DB', 'DUPLICATE', 'existing active value', 'Existing duplicate is identified'),
 ('MASTER_FK_MISSING', 'REFERENTIAL', 'unknown parent', 'Rejected with parent relationship guidance'),
 ('MASTER_IN_USE_DELETE', 'LIFECYCLE', 'referenced record', 'Soft delete is refused with dependency count'),
 ('MASTER_UNUSED_DELETE', 'LIFECYCLE', 'unreferenced record', 'Soft deleted; no hard delete occurs'),
 ('MASTER_DELETED_REUSE', 'LIFECYCLE', 'previously deleted value', 'Behaviour follows the master uniqueness policy'),
 ('MASTER_BOM_CRLF', 'FILE_FORMAT', 'UTF-8 BOM and CRLF', 'Template imports successfully'),
 ('MASTER_QUOTED_COMMA', 'FILE_FORMAT', '"North, West"', 'Comma remains part of one field'),
 ('MASTER_ESCAPED_QUOTE', 'FILE_FORMAT', '"A ""quoted"" name"', 'Quote is decoded correctly'),
 ('MASTER_EMPTY_FILE', 'FILE_FORMAT', null, 'Rejected with no writes'),
 ('MASTER_HEADER_ONLY', 'FILE_FORMAT', 'header only', 'Rejected with no writes'),
 ('MASTER_WRONG_HEADER', 'FILE_FORMAT', 'renamed column', 'Rejected and missing columns listed'),
 ('MASTER_5000_ROWS', 'CAPACITY', '5000 valid rows', 'Accepted atomically inside the limit'),
 ('MASTER_5001_ROWS', 'CAPACITY', '5001 valid rows', 'Rejected before writes'),
 ('MASTER_5MB', 'CAPACITY', 'exactly 5 MiB', 'Accepted when row count is also valid'),
 ('MASTER_OVER_5MB', 'CAPACITY', '5 MiB plus one byte', 'Rejected before parsing')
on conflict (case_code) do update
set category = excluded.category,
    value_sample = excluded.value_sample,
    expected_result = excluded.expected_result;

analyze qa.scale_ordinal;

-- Fast proof: cardinality is derived from the common million-row ordinal.
select screen_code, target_rows
from qa.screen_scale_target
where data_class = 'TRANSACTION'
order by screen_code;
