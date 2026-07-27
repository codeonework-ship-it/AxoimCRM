package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Auditable provider certification register. It records evidence; it never self-awards a vendor badge. */
@Service
public class IdentityCertificationService {
    public static final List<String> REQUIRED = List.of(
            "federatedLogin", "issuerAudienceNonceValidated", "scimDiscovery", "scimUserCreate",
            "scimUserUpdate", "scimUserDeactivate", "sessionsRevoked", "ownedRecordsPreserved",
            "scimGroupCreate", "scimGroupMembership", "scimGroupDeactivate", "filterAndPagination");

    private final JdbcTemplate jdbc; private final ObjectMapper json; private final AuditService audit;
    public IdentityCertificationService(JdbcTemplate jdbc,ObjectMapper json,AuditService audit){this.jdbc=jdbc;this.json=json;this.audit=audit;}
    public record Request(UUID idpConfigId,String provider,String externalTenantRef,String connectorJobRef,Map<String,Boolean> evidence){}
    public record Row(UUID id,UUID idpConfigId,String provider,String externalTenantRef,String connectorJobRef,
                      String status,Map<String,Boolean> evidence,List<String> missing,Instant startedAt,Instant completedAt){}

    @Transactional(readOnly=true)
    public List<Row> list(){requireViewer();return jdbc.query("""
            select id,idp_config_id,provider,external_tenant_ref,connector_job_ref,status,evidence::text,started_at,completed_at
            from identity.idp_certification_run where tenant_id=? order by started_at desc limit 100
            """,(rs,i)->map(rs),TenantContext.get().tenantId());}

    @Transactional
    public Row record(Request request){requireAdmin();String provider=required(request.provider(),"Name the provider, for example MICROSOFT_ENTRA_ID or OKTA");
        String external=required(request.externalTenantRef(),"The external provider tenant/reference is required for production evidence");
        String job=required(request.connectorJobRef(),"The provider connector/job reference is required for production evidence");
        Map<String,Boolean> evidence=new LinkedHashMap<>();for(String key:REQUIRED)evidence.put(key,Boolean.TRUE.equals(request.evidence()==null?null:request.evidence().get(key)));
        List<String> missing=missing(evidence);String status=missing.isEmpty()?"PASSED":"FAILED";UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into identity.idp_certification_run
                  (id,tenant_id,idp_config_id,provider,external_tenant_ref,connector_job_ref,status,evidence,requested_by,completed_at)
                values (?,?,?,?,?,?,?,?::jsonb,?,now())
                """,id,TenantContext.get().tenantId(),request.idpConfigId(),provider,external,job,status,write(evidence),TenantContext.get().userId());
        audit.record("IDENTITY_PROVIDER_CERTIFICATION","IDP_CERTIFICATION",id,
                "Identity provider certification recorded as "+status,Map.of("provider",provider,"missing",missing.toString(),"externalTenantRef",external,"connectorJobRef",job));
        return list().stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();}

    private Row map(java.sql.ResultSet rs)throws java.sql.SQLException{Map<String,Boolean> evidence=read(rs.getString("evidence"));List<String> missing=missing(evidence);return new Row(rs.getObject("id",UUID.class),rs.getObject("idp_config_id",UUID.class),rs.getString("provider"),rs.getString("external_tenant_ref"),rs.getString("connector_job_ref"),rs.getString("status"),evidence,missing,rs.getTimestamp("started_at").toInstant(),rs.getTimestamp("completed_at")==null?null:rs.getTimestamp("completed_at").toInstant());}
    static List<String> missing(Map<String,Boolean> evidence){return REQUIRED.stream().filter(k->!Boolean.TRUE.equals(evidence.get(k))).toList();}
    private Map<String,Boolean> read(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){return Map.of();}}
    private String write(Map<String,Boolean> value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Certification evidence is invalid",e);}}
    private static String required(String value,String message){if(value==null||value.isBlank())throw new IllegalArgumentException(message);return value.trim();}
    private static void requireAdmin(){CrmRole role=CrmRole.current(TenantContext.get().role());if(role!=CrmRole.SUPER_ADMIN&&role!=CrmRole.TENANT_ADMIN)throw new ForbiddenException("Identity certification requires Super Admin or Tenant Admin");}
    private static void requireViewer(){CrmRole role=CrmRole.current(TenantContext.get().role());if(role!=CrmRole.SUPER_ADMIN&&role!=CrmRole.TENANT_ADMIN&&role!=CrmRole.SUPER_AUDIT&&role!=CrmRole.AUDITOR)throw new ForbiddenException("Identity certification is visible to administrators and auditors");}
}
