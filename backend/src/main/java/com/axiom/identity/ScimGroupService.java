package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** SCIM Group resources backed by E02 security.user_group and exact membership replacement. */
@Service
public class ScimGroupService {
    public static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    private static final int MAX_PAGE = 200;
    private static final Pattern MEMBER_FILTER = Pattern.compile(
            "^members\\[value\\s+eq\\s+\\\"([0-9a-fA-F-]{36})\\\"\\]$", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ScimGroupService(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String filter, Integer startIndex, Integer count) {
        UUID tenant = TenantContext.get().tenantId();
        int start = startIndex == null || startIndex < 1 ? 1 : startIndex;
        int size = count == null || count < 1 ? 50 : Math.min(count, MAX_PAGE);
        String name = parseFilter(filter);
        String predicate = name == null ? "" : " and lower(l.display_name)=lower(?)";
        List<Object> args = new ArrayList<>(List.of(tenant)); if (name != null) args.add(name);
        Integer total = jdbc.queryForObject("select count(*) from identity.scim_group_link l join security.user_group g "
                        + "on g.tenant_id=l.tenant_id and g.id=l.user_group_id where l.tenant_id=? and g.active" + predicate,
                Integer.class, args.toArray());
        args.add(size); args.add(start - 1);
        List<Map<String, Object>> resources = jdbc.query("""
                select l.id,l.external_id,l.display_name,l.version,l.created_at,l.updated_at,l.user_group_id,g.active
                from identity.scim_group_link l join security.user_group g
                  on g.tenant_id=l.tenant_id and g.id=l.user_group_id
                where l.tenant_id=? and g.active
                """ + predicate + " order by l.display_name limit ? offset ?",
                (rs, i) -> resource(rs.getObject("id", UUID.class), rs.getString("external_id"),
                        rs.getString("display_name"), rs.getObject("user_group_id", UUID.class),
                        rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getBoolean("active")), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemas", List.of(ScimUserService.LIST_SCHEMA)); out.put("totalResults", total == null ? 0 : total);
        out.put("startIndex", start); out.put("itemsPerPage", resources.size()); out.put("Resources", resources);
        return out;
    }

    @Transactional(readOnly = true) public Map<String, Object> get(UUID id) { return fetch(id); }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        UUID tenant = TenantContext.get().tenantId();
        String name = required(payload.get("displayName"), "displayName is required for a SCIM group");
        UUID groupId = UUID.randomUUID(); UUID linkId = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into security.user_group(id,tenant_id,code,name,description,created_by)
                    values (?,?,?,?,?,?)
                    """, groupId, tenant, code(name, linkId), name, "Directory-managed SCIM group",
                    TenantContext.get().userId());
            jdbc.update("""
                    insert into identity.scim_group_link(id,tenant_id,external_id,display_name,user_group_id)
                    values (?,?,?,?,?)
                    """, linkId, tenant, string(payload.get("externalId")), name, groupId);
            replaceMembers(groupId, members(payload.get("members")));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A SCIM group with that externalId already exists in this workspace");
        }
        audit.record("SCIM_GROUP_CREATE", "USER_GROUP", groupId, "Directory group provisioned: " + name,
                Map.of("scimGroupId", linkId.toString(), "memberCount", members(payload.get("members")).size()));
        return fetch(linkId);
    }

    @Transactional
    public Map<String, Object> replace(UUID id, Map<String, Object> payload) {
        Row row = row(id); String name = required(payload.get("displayName"), "displayName is required");
        jdbc.update("update security.user_group set name=?,active=true where tenant_id=? and id=?",
                name, TenantContext.get().tenantId(), row.groupId());
        jdbc.update("""
                update identity.scim_group_link set display_name=?,external_id=coalesce(?,external_id),
                  version=version+1,updated_at=now() where tenant_id=? and id=?
                """, name, string(payload.get("externalId")), TenantContext.get().tenantId(), id);
        replaceMembers(row.groupId(), members(payload.get("members")));
        audit.record("SCIM_GROUP_REPLACE", "USER_GROUP", row.groupId(), "Directory group replaced: " + name,
                Map.of("scimGroupId", id.toString()));
        return fetch(id);
    }

    @Transactional
    public Map<String, Object> patch(UUID id, Map<String, Object> payload) {
        Row row = row(id);
        Object raw = payload.get("Operations");
        if (!(raw instanceof List<?> operations) || operations.isEmpty()) throw new IllegalArgumentException("PATCH requires Operations");
        for (Object item : operations) {
            if (!(item instanceof Map<?, ?> operation)) throw new IllegalArgumentException("Each patch operation must be an object");
            String op = string(operation.get("op")); String path = string(operation.get("path")); Object value = operation.get("value");
            if (op == null) throw new IllegalArgumentException("Patch operation is missing op");
            if ("displayName".equalsIgnoreCase(path)) {
                if (!"replace".equalsIgnoreCase(op)) throw new IllegalArgumentException("displayName supports replace only");
                String name = required(value, "displayName cannot be empty");
                jdbc.update("update security.user_group set name=? where tenant_id=? and id=?", name,
                        TenantContext.get().tenantId(), row.groupId());
                jdbc.update("update identity.scim_group_link set display_name=?,version=version+1,updated_at=now() where tenant_id=? and id=?",
                        name, TenantContext.get().tenantId(), id);
                continue;
            }
            if (path == null || "members".equalsIgnoreCase(path)) {
                List<UUID> ids = members(value);
                if ("replace".equalsIgnoreCase(op)) replaceMembers(row.groupId(), ids);
                else if ("add".equalsIgnoreCase(op)) addMembers(row.groupId(), ids);
                else if ("remove".equalsIgnoreCase(op)) removeMembers(row.groupId(), ids);
                else throw new IllegalArgumentException("Unsupported group patch operation " + op);
                touch(id); continue;
            }
            var matcher = MEMBER_FILTER.matcher(path);
            if (matcher.matches() && "remove".equalsIgnoreCase(op)) {
                removeMembers(row.groupId(), List.of(UUID.fromString(matcher.group(1)))); touch(id); continue;
            }
            throw new IllegalArgumentException("Unsupported group patch path " + path);
        }
        audit.record("SCIM_GROUP_PATCH", "USER_GROUP", row.groupId(), "Directory group membership patched",
                Map.of("scimGroupId", id.toString(), "operations", operations.size()));
        return fetch(id);
    }

    @Transactional
    public void deactivate(UUID id) {
        Row row = row(id);
        jdbc.update("delete from security.user_group_member where tenant_id=? and group_id=?",
                TenantContext.get().tenantId(), row.groupId());
        jdbc.update("update security.user_group set active=false where tenant_id=? and id=?",
                TenantContext.get().tenantId(), row.groupId());
        touch(id);
        audit.record("SCIM_GROUP_DEPROVISION", "USER_GROUP", row.groupId(),
                "Directory group deactivated; the security master was retained", Map.of("scimGroupId", id.toString()));
    }

    private Map<String, Object> fetch(UUID id) {
        Row row = row(id);
        if (!row.active()) throw new NotFoundException("No active SCIM group with that id exists");
        return resource(row.id(), row.externalId(), row.name(), row.groupId(), row.version(), row.created(), row.updated(), row.active());
    }
    private Row row(UUID id) {
        try {
            return jdbc.queryForObject("""
                    select l.id,l.external_id,l.display_name,l.user_group_id,l.version,l.created_at,l.updated_at,g.active
                    from identity.scim_group_link l join security.user_group g
                      on g.tenant_id=l.tenant_id and g.id=l.user_group_id
                    where l.tenant_id=? and l.id=?
                    """, (rs,i)->new Row(rs.getObject("id",UUID.class),rs.getString("external_id"),
                    rs.getString("display_name"),rs.getObject("user_group_id",UUID.class),rs.getLong("version"),
                    rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),rs.getBoolean("active")),
                    TenantContext.get().tenantId(), id);
        } catch (EmptyResultDataAccessException e) { throw new NotFoundException("No SCIM group with that id exists"); }
    }
    private Map<String, Object> resource(UUID id,String external,String name,UUID group,long version,Instant created,Instant updated,boolean active) {
        List<Map<String,Object>> members=jdbc.query("""
                select u.id,u.display_name from security.user_group_member m join identity.app_user u
                  on u.tenant_id=m.tenant_id and u.id=m.user_id where m.tenant_id=? and m.group_id=? order by u.display_name
                """,(rs,i)->Map.of("value",rs.getObject("id",UUID.class).toString(),"display",rs.getString("display_name"),
                "$ref","/scim/v2/Users/"+rs.getObject("id",UUID.class)),TenantContext.get().tenantId(),group);
        Map<String,Object> meta=new LinkedHashMap<>(); meta.put("resourceType","Group");meta.put("created",created.toString());
        meta.put("lastModified",updated.toString());meta.put("version","W/\""+version+"\"");meta.put("location","/scim/v2/Groups/"+id);
        Map<String,Object> out=new LinkedHashMap<>();out.put("schemas",List.of(GROUP_SCHEMA));out.put("id",id.toString());
        if(external!=null)out.put("externalId",external);out.put("displayName",name);out.put("members",members);out.put("active",active);out.put("meta",meta);return out;
    }
    private void replaceMembers(UUID group,List<UUID> ids){jdbc.update("delete from security.user_group_member where tenant_id=? and group_id=?",TenantContext.get().tenantId(),group);addMembers(group,ids);}
    private void addMembers(UUID group,List<UUID> ids){for(UUID user:ids){Integer n=jdbc.queryForObject("select count(*) from identity.app_user where tenant_id=? and id=?",Integer.class,TenantContext.get().tenantId(),user);if(n==null||n==0)throw new NotFoundException("Group member "+user+" is not a user in this workspace");jdbc.update("insert into security.user_group_member(tenant_id,group_id,user_id) values (?,?,?) on conflict do nothing",TenantContext.get().tenantId(),group,user);}}
    private void removeMembers(UUID group,List<UUID> ids){for(UUID user:ids)jdbc.update("delete from security.user_group_member where tenant_id=? and group_id=? and user_id=?",TenantContext.get().tenantId(),group,user);}
    private void touch(UUID id){jdbc.update("update identity.scim_group_link set version=version+1,updated_at=now() where tenant_id=? and id=?",TenantContext.get().tenantId(),id);}
    private static List<UUID> members(Object raw){if(raw==null)return List.of();if(!(raw instanceof List<?> list))throw new IllegalArgumentException("members must be an array");List<UUID> out=new ArrayList<>();for(Object item:list){String value=item instanceof Map<?,?> map?string(map.get("value")):string(item);if(value!=null)out.add(UUID.fromString(value));}return out;}
    static String parseFilter(String filter){if(filter==null||filter.isBlank())return null;var m=Pattern.compile("^displayName\\s+eq\\s+\\\"([^\\\"]+)\\\"$",Pattern.CASE_INSENSITIVE).matcher(filter.trim());if(!m.matches())throw new IllegalArgumentException("Groups support the filter displayName eq \\\"value\\\"");return m.group(1);}
    private static String code(String name,UUID id){String base=name.toUpperCase().replaceAll("[^A-Z0-9]+","_").replaceAll("^_+|_+$","");if(base.isBlank()||!Character.isLetter(base.charAt(0)))base="GROUP_"+base;return ("SCIM_"+base+"_"+id.toString().substring(0,8).toUpperCase()).substring(0,Math.min(100,("SCIM_"+base+"_"+id.toString().substring(0,8)).length()));}
    private static String required(Object v,String message){String s=string(v);if(s==null||s.isBlank())throw new IllegalArgumentException(message);return s.trim();}
    private static String string(Object v){return v==null?null:String.valueOf(v);}
    private record Row(UUID id,String externalId,String name,UUID groupId,long version,Instant created,Instant updated,boolean active){}
}
