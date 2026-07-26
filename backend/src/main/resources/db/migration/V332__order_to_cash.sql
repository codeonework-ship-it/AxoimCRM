-- =============================================================================
-- V332 — order to cash: order lines, procurement, and customer receivables.
--
-- What already existed and is therefore NOT recreated here:
--   contracting.order_record  a sales-order header with a real state machine
--                             (DRAFT/BOOKED/FULFILMENT/PARTIALLY_FULFILLED/
--                             FULFILLED/CANCELLED), tenant-composite FKs and RLS
--   cpq.quote_order           the quote -> order link, complete but referenced by
--                             no code at all until now
--   billing.invoice           Axiom billing ITS OWN tenants. Deliberately left
--                             alone: a tenant invoicing its customers is a
--                             different ledger with different owners, retention
--                             and permissions, and merging the two would let a
--                             customer AR query read platform revenue.
--
-- What this adds: the line level the order header never had, purchasing, and a
-- customer-facing receivables ledger.
-- =============================================================================

create schema if not exists procurement;
create schema if not exists receivables;

-- -----------------------------------------------------------------------------
-- Governance columns the order header was missing.
--
-- Without `version` an order cannot be edited safely — two people booking the
-- same order would last-write-wins, and an order is a commitment to a customer.
-- Added rather than assumed: every other authored record in this schema carries
-- the same four.
-- -----------------------------------------------------------------------------
alter table contracting.order_record
  add column if not exists owner_id    uuid,
  add column if not exists updated_at  timestamptz not null default now(),
  add column if not exists created_by  uuid,
  add column if not exists updated_by  uuid,
  add column if not exists version     bigint not null default 0,
  add column if not exists deleted_at  timestamptz,
  add column if not exists deleted_by  uuid,
  add column if not exists booked_at   timestamptz,
  add column if not exists cancelled_reason text;

-- -----------------------------------------------------------------------------
-- Sales order lines.
--
-- Amounts are stored, not derived on read. A line's extended amount is the
-- number the customer agreed to; recomputing it later from quantity x price
-- would silently restate history the first time a price book changes. The
-- service recalculates on write and the stored value is the record.
-- -----------------------------------------------------------------------------
create table if not exists contracting.order_line (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  order_id         uuid not null,
  line_number      integer not null,
  product_id       uuid,
  product_code     text,
  product_name     text not null,
  unit_of_measure  text not null default 'EA',
  quantity         numeric(18,4) not null check (quantity > 0),
  unit_price       numeric(18,4) not null check (unit_price >= 0),
  discount_pct     numeric(7,4) not null default 0 check (discount_pct >= 0 and discount_pct <= 100),
  extended_amount  numeric(18,2) not null check (extended_amount >= 0),
  currency_code    text not null default 'INR',
  -- Fulfilment is per line: a two-line order can ship one line and backorder the
  -- other, and an order-level flag cannot express that.
  quantity_fulfilled numeric(18,4) not null default 0 check (quantity_fulfilled >= 0),
  -- Where the line came from, so a converted order can be traced to its quote.
  source_quote_line_id uuid,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  constraint fk_order_line_order_same_tenant
    foreign key (tenant_id, order_id) references contracting.order_record(tenant_id, id) on delete cascade,
  constraint uq_order_line_number unique (tenant_id, order_id, line_number),
  -- Cannot fulfil more than was ordered. A database-level guarantee because
  -- over-fulfilment corrupts revenue recognition, not just a screen.
  constraint order_line_not_over_fulfilled check (quantity_fulfilled <= quantity)
);

create index if not exists idx_order_line_order on contracting.order_line (tenant_id, order_id);
create unique index if not exists uq_order_line_source
  on contracting.order_line (tenant_id, source_quote_line_id)
  where source_quote_line_id is not null;

-- -----------------------------------------------------------------------------
-- Vendors.
--
-- Separate from crm.account on purpose. An account is somebody you sell to; a
-- vendor is somebody you buy from. They carry different identifiers (tax and
-- registration numbers, payment terms, bank details), different approval paths,
-- and different visibility — a salesperson should not see supplier pricing.
-- The same legal entity can be both, which `linked_account_id` records without
-- forcing one table to mean two things.
-- -----------------------------------------------------------------------------
create table if not exists procurement.vendor (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  vendor_code        text not null,
  name               text not null,
  legal_name         text,
  status             text not null default 'ACTIVE'
                     check (status in ('DRAFT', 'ACTIVE', 'ON_HOLD', 'BLOCKED', 'INACTIVE')),
  category           text,
  tax_registration   text,
  payment_terms      text not null default 'NET30',
  currency_code      text not null default 'INR',
  primary_email      text,
  primary_phone      text,
  address_line1      text,
  city               text,
  country_code       text,
  -- The same legal entity as a customer account, when it is one.
  linked_account_id  uuid,
  owner_id           uuid,
  notes              text,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  created_by         uuid,
  updated_by         uuid,
  deleted_at         timestamptz,
  deleted_by         uuid,
  version            bigint not null default 0,
  unique (tenant_id, id),
  constraint uq_vendor_code unique (tenant_id, vendor_code),
  constraint fk_vendor_account_same_tenant
    foreign key (tenant_id, linked_account_id) references crm.account(tenant_id, id)
);

create index if not exists idx_vendor_status on procurement.vendor (tenant_id, status) where deleted_at is null;

-- -----------------------------------------------------------------------------
-- Purchase orders.
--
-- The state machine mirrors the sales-order one in shape but not in values,
-- because purchasing has an approval step selling does not: a PO commits money
-- outward and someone other than the raiser must approve it.
-- -----------------------------------------------------------------------------
create table if not exists procurement.purchase_order (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  po_number         text not null,
  vendor_id         uuid not null,
  status            text not null default 'DRAFT'
                    check (status in ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SENT',
                                      'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
  order_date        date not null default current_date,
  expected_at       date,
  currency_code     text not null default 'INR',
  subtotal_amount   numeric(18,2) not null default 0 check (subtotal_amount >= 0),
  tax_amount        numeric(18,2) not null default 0 check (tax_amount >= 0),
  total_amount      numeric(18,2) not null default 0 check (total_amount >= 0),
  -- Segregation of duties: whoever approves must not be whoever raised it. The
  -- CHECK makes that structural rather than a rule a service might forget.
  requested_by      uuid,
  approved_by       uuid,
  approved_at       timestamptz,
  approval_note     text,
  cancelled_reason  text,
  notes             text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  created_by        uuid,
  updated_by        uuid,
  deleted_at        timestamptz,
  deleted_by        uuid,
  version           bigint not null default 0,
  unique (tenant_id, id),
  constraint uq_po_number unique (tenant_id, po_number),
  constraint fk_po_vendor_same_tenant
    foreign key (tenant_id, vendor_id) references procurement.vendor(tenant_id, id),
  constraint po_approver_is_not_requester
    check (approved_by is null or requested_by is null or approved_by <> requested_by),
  constraint po_approved_has_approver
    check (status not in ('APPROVED', 'SENT', 'PARTIALLY_RECEIVED', 'RECEIVED')
           or approved_by is not null)
);

create index if not exists idx_po_vendor on procurement.purchase_order (tenant_id, vendor_id) where deleted_at is null;
create index if not exists idx_po_status on procurement.purchase_order (tenant_id, status) where deleted_at is null;

create table if not exists procurement.purchase_order_line (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  purchase_order_id  uuid not null,
  line_number        integer not null,
  product_id         uuid,
  description        text not null,
  unit_of_measure    text not null default 'EA',
  quantity           numeric(18,4) not null check (quantity > 0),
  unit_price         numeric(18,4) not null check (unit_price >= 0),
  tax_pct            numeric(7,4) not null default 0 check (tax_pct >= 0),
  extended_amount    numeric(18,2) not null check (extended_amount >= 0),
  quantity_received  numeric(18,4) not null default 0 check (quantity_received >= 0),
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint fk_po_line_po_same_tenant
    foreign key (tenant_id, purchase_order_id)
      references procurement.purchase_order(tenant_id, id) on delete cascade,
  constraint uq_po_line_number unique (tenant_id, purchase_order_id, line_number),
  constraint po_line_not_over_received check (quantity_received <= quantity)
);

create index if not exists idx_po_line_po
  on procurement.purchase_order_line (tenant_id, purchase_order_id);

-- -----------------------------------------------------------------------------
-- Customer receivables.
--
-- `receivables.invoice` is what a tenant sends its own customers. It is not
-- billing.invoice, which is what Axiom sends the tenant. Two ledgers, two
-- audiences, two retention rules — and keeping them apart is what stops a
-- customer-facing AR report from reaching platform revenue.
-- -----------------------------------------------------------------------------
create table if not exists receivables.invoice (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  invoice_number   text not null,
  account_id       uuid not null,
  order_id         uuid,
  status           text not null default 'DRAFT'
                   check (status in ('DRAFT', 'ISSUED', 'PART_PAID', 'PAID', 'OVERDUE',
                                     'CREDITED', 'CANCELLED', 'WRITTEN_OFF')),
  issue_date       date,
  due_date         date,
  currency_code    text not null default 'INR',
  subtotal_amount  numeric(18,2) not null default 0 check (subtotal_amount >= 0),
  tax_amount       numeric(18,2) not null default 0 check (tax_amount >= 0),
  total_amount     numeric(18,2) not null default 0 check (total_amount >= 0),
  -- Maintained by the payment path, not recomputed on read: an AR ageing report
  -- that re-derives paid amounts is a report that disagrees with the ledger the
  -- moment a payment is voided.
  paid_amount      numeric(18,2) not null default 0 check (paid_amount >= 0),
  notes            text,
  cancelled_reason text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  created_by       uuid,
  updated_by       uuid,
  deleted_at       timestamptz,
  deleted_by       uuid,
  version          bigint not null default 0,
  unique (tenant_id, id),
  constraint uq_invoice_number unique (tenant_id, invoice_number),
  constraint fk_invoice_account_same_tenant
    foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  constraint fk_invoice_order_same_tenant
    foreign key (tenant_id, order_id) references contracting.order_record(tenant_id, id),
  -- An issued invoice must state when it is due; a DRAFT need not.
  constraint invoice_issued_has_dates
    check (status = 'DRAFT' or (issue_date is not null and due_date is not null)),
  constraint invoice_not_overpaid check (paid_amount <= total_amount)
);

create index if not exists idx_invoice_account on receivables.invoice (tenant_id, account_id) where deleted_at is null;
create index if not exists idx_invoice_status_due
  on receivables.invoice (tenant_id, status, due_date) where deleted_at is null;

create table if not exists receivables.invoice_line (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  invoice_id       uuid not null,
  line_number      integer not null,
  description      text not null,
  quantity         numeric(18,4) not null check (quantity > 0),
  unit_price       numeric(18,4) not null check (unit_price >= 0),
  tax_pct          numeric(7,4) not null default 0 check (tax_pct >= 0),
  extended_amount  numeric(18,2) not null check (extended_amount >= 0),
  source_order_line_id uuid,
  created_at       timestamptz not null default now(),
  constraint fk_invoice_line_invoice_same_tenant
    foreign key (tenant_id, invoice_id) references receivables.invoice(tenant_id, id) on delete cascade,
  constraint uq_invoice_line_number unique (tenant_id, invoice_id, line_number)
);

create index if not exists idx_invoice_line_invoice on receivables.invoice_line (tenant_id, invoice_id);

create table if not exists receivables.payment (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  invoice_id     uuid not null,
  amount         numeric(18,2) not null check (amount > 0),
  currency_code  text not null default 'INR',
  received_at    date not null default current_date,
  method         text not null default 'BANK_TRANSFER'
                 check (method in ('BANK_TRANSFER', 'CARD', 'CHEQUE', 'CASH', 'OFFSET', 'OTHER')),
  reference      text,
  -- A voided payment stays on the ledger. Deleting it would make the invoice's
  -- paid_amount unexplainable from its own history.
  voided_at      timestamptz,
  voided_by      uuid,
  void_reason    text,
  created_at     timestamptz not null default now(),
  created_by     uuid,
  constraint fk_payment_invoice_same_tenant
    foreign key (tenant_id, invoice_id) references receivables.invoice(tenant_id, id)
);

create index if not exists idx_payment_invoice on receivables.payment (tenant_id, invoice_id);

-- -----------------------------------------------------------------------------
-- Row-level security on everything new (ADR-001).
-- -----------------------------------------------------------------------------
do $$
declare t text;
begin
  foreach t in array array[
    'contracting.order_line',
    'procurement.vendor',
    'procurement.purchase_order',
    'procurement.purchase_order_line',
    'receivables.invoice',
    'receivables.invoice_line',
    'receivables.payment'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format('drop policy if exists tenant_isolation on %s', t);
    execute format($f$
      create policy tenant_isolation on %s
        using (tenant_id = current_setting('app.tenant_id', true)::uuid)
        with check (tenant_id = current_setting('app.tenant_id', true)::uuid)
    $f$, t);
    execute format('grant select, insert, update, delete on %s to axiom_app', t);
  end loop;
end $$;
