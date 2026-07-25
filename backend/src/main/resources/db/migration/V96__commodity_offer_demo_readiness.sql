-- Keep the commodity demo transaction ready for the governed Offer command.
-- V95 remains immutable after release; this forward migration only adjusts seed state.

update commodity.counterparty_profile
set credit_limit = greatest(credit_limit, 2000000.00::numeric),
    updated_at = now()
where tenant_id = '11111111-1111-1111-1111-111111111111'
  and counterparty_code = 'CP-CASTELLAN';

update commodity.contract_term_sheet ts
set status = 'APPROVED'
from commodity.trade_enquiry e
where ts.tenant_id = e.tenant_id
  and ts.trade_enquiry_id = e.id
  and e.tenant_id = '11111111-1111-1111-1111-111111111111'
  and e.enquiry_number = 'CTR-ENQ-2026-0001'
  and ts.term_sheet_number = 'TS-2026-0001'
  and ts.status not in ('SENT', 'ACCEPTED');
