-- Keep workflow guidance user-actionable. These timestamps are written by the
-- governed command and must never be presented as fields an operator must fill.

update automation.process_state s
set mandatory_fields = '{sandbox_code,name,sandbox_type,source_environment,owner_id}'::text[]
from automation.process_definition p
where p.id = s.process_id
  and p.tenant_id = s.tenant_id
  and p.process_code = 'PRC-SANDBOX-LIFECYCLE'
  and s.state_code = 'ACTIVE';

update automation.process_state s
set mandatory_fields = '{pack_code,name,scope,generated_by}'::text[]
from automation.process_definition p
where p.id = s.process_id
  and p.tenant_id = s.tenant_id
  and p.process_code = 'PRC-EVIDENCE-PACK-LIFECYCLE'
  and s.state_code in ('READY','EXPORTED');

update automation.process_state s
set mandatory_fields = '{onboarding_number,account_id,client_type,risk_rating,owner_id,due_at}'::text[]
from automation.process_definition p
where p.id = s.process_id
  and p.tenant_id = s.tenant_id
  and p.process_code = 'PRC-BFSI-KYC-LIFECYCLE'
  and s.state_code in ('CLEARED','REJECTED');
