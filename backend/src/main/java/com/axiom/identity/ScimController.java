package com.axiom.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SCIM 2.0 endpoints (FR-TEN-007). Authenticated by {@link ScimAuthFilter} using an
 * {@code identity.scim_token} bearer token, never a user access token.
 *
 * <p>Responses carry the {@code application/scim+json} content type and SCIM's own
 * envelope shapes, so a standards-compliant client can consume them without
 * special-casing Axiom.
 *
 * <p>Users and Groups are mapped to tenant-owned identity/security masters.
 * Deprovisioning is a soft lifecycle transition so ownership attribution and
 * audit evidence remain intact.
 */
@RestController
@RequestMapping(value = "/scim/v2", produces = "application/scim+json")
public class ScimController {

    private final ScimUserService users;
    private final ScimGroupService groups;

    public ScimController(ScimUserService users, ScimGroupService groups) {
        this.users = users;
        this.groups = groups;
    }

    @GetMapping("/Users")
    public Map<String, Object> listUsers(@RequestParam(required = false) String filter,
                                         @RequestParam(required = false) Integer startIndex,
                                         @RequestParam(required = false) Integer count) {
        return users.listUsers(filter, startIndex, count);
    }

    @GetMapping("/Users/{id}")
    public Map<String, Object> getUser(@PathVariable UUID id) {
        return users.getUser(id);
    }

    @PostMapping(value = "/Users", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/scim+json"})
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> payload) {
        Map<String, Object> created = users.createUser(payload);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/scim/v2/Users/" + created.get("id"))
                .body(created);
    }

    @PutMapping(value = "/Users/{id}", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/scim+json"})
    public Map<String, Object> replaceUser(@PathVariable UUID id, @RequestBody Map<String, Object> payload) {
        return users.replaceUser(id, payload);
    }

    @PatchMapping(value = "/Users/{id}", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/scim+json"})
    public Map<String, Object> patchUser(@PathVariable UUID id, @RequestBody Map<String, Object> payload) {
        return users.patchUser(id, payload);
    }

    /**
     * Deprovision. Returns 204 as SCIM requires, but the user is deactivated rather
     * than deleted so the records they own stay intact and attributed (FR-TEN-007).
     */
    @DeleteMapping("/Users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        users.deprovisionUser(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/Groups")
    public Map<String, Object> listGroups(@RequestParam(required = false) String filter,
                                          @RequestParam(required = false) Integer startIndex,
                                          @RequestParam(required = false) Integer count) {
        return groups.list(filter, startIndex, count);
    }
    @GetMapping("/Groups/{id}") public Map<String,Object> getGroup(@PathVariable UUID id){return groups.get(id);}
    @PostMapping(value="/Groups",consumes={MediaType.APPLICATION_JSON_VALUE,"application/scim+json"})
    public ResponseEntity<Map<String,Object>> createGroup(@RequestBody Map<String,Object> body){Map<String,Object> created=groups.create(body);return ResponseEntity.status(HttpStatus.CREATED).header("Location","/scim/v2/Groups/"+created.get("id")).body(created);}
    @PutMapping(value="/Groups/{id}",consumes={MediaType.APPLICATION_JSON_VALUE,"application/scim+json"})
    public Map<String,Object> replaceGroup(@PathVariable UUID id,@RequestBody Map<String,Object> body){return groups.replace(id,body);}
    @PatchMapping(value="/Groups/{id}",consumes={MediaType.APPLICATION_JSON_VALUE,"application/scim+json"})
    public Map<String,Object> patchGroup(@PathVariable UUID id,@RequestBody Map<String,Object> body){return groups.patch(id,body);}
    @DeleteMapping("/Groups/{id}") public ResponseEntity<Void> deleteGroup(@PathVariable UUID id){groups.deactivate(id);return ResponseEntity.noContent().build();}

    @GetMapping("/Schemas")
    public Map<String,Object> schemas(){return Map.of("schemas",List.of(ScimUserService.LIST_SCHEMA),"totalResults",3,"startIndex",1,"itemsPerPage",3,"Resources",List.of(
            schema(ScimUserService.USER_SCHEMA,"User",List.of("userName","displayName","active","emails")),
            schema(ScimGroupService.GROUP_SCHEMA,"Group",List.of("displayName","members")),
            schema(ScimUserService.AXIOM_EXTENSION,"Axiom User Extension",List.of("role"))));}
    @GetMapping("/Schemas/{urn}") public Map<String,Object> schema(@PathVariable String urn){return switch(urn){case ScimUserService.USER_SCHEMA->schema(urn,"User",List.of("userName","displayName","active","emails"));case ScimGroupService.GROUP_SCHEMA->schema(urn,"Group",List.of("displayName","members"));case ScimUserService.AXIOM_EXTENSION->schema(urn,"Axiom User Extension",List.of("role"));default->throw new com.axiom.common.NotFoundException("Unknown SCIM schema");};}
    @GetMapping("/ResourceTypes") public Map<String,Object> resourceTypes(){return Map.of("schemas",List.of(ScimUserService.LIST_SCHEMA),"totalResults",2,"startIndex",1,"itemsPerPage",2,"Resources",List.of(resourceType("User","/Users",ScimUserService.USER_SCHEMA),resourceType("Group","/Groups",ScimGroupService.GROUP_SCHEMA)));}
    @GetMapping("/ResourceTypes/{name}") public Map<String,Object> resourceType(@PathVariable String name){return "User".equalsIgnoreCase(name)?resourceType("User","/Users",ScimUserService.USER_SCHEMA):"Group".equalsIgnoreCase(name)?resourceType("Group","/Groups",ScimGroupService.GROUP_SCHEMA):throwNotFound();}

    @GetMapping("/ServiceProviderConfig")
    public Map<String, Object> serviceProviderConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));
        config.put("documentationUri", "/docs/manual/user-guide.md#identity--access");
        config.put("patch", Map.of("supported", true));
        config.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
        config.put("filter", Map.of("supported", true, "maxResults", 200,
                "note", "Users support userName eq \"value\" and active eq true|false; Groups support displayName eq \"value\""));
        config.put("changePassword", Map.of("supported", false,
                "note", "SCIM-provisioned users authenticate through the directory, not with a local password"));
        config.put("sort", Map.of("supported", false));
        config.put("etag", Map.of("supported", false));
        config.put("authenticationSchemes", List.of(Map.of(
                "type", "oauthbearertoken",
                "name", "Axiom SCIM token",
                "description", "A workspace-scoped provisioning token issued from Sessions & Security")));
        config.put("resourceTypesSupported", List.of("User", "Group"));
        config.put("groupsSupported", true);
        config.put("note", "Directory groups are retained as governed E02 security user-group masters; delete deactivates the group and preserves audit evidence.");
        return config;
    }

    private static Map<String,Object> schema(String id,String name,List<String> attributes){return Map.of("schemas",List.of("urn:ietf:params:scim:schemas:core:2.0:Schema"),"id",id,"name",name,"attributes",attributes.stream().map(a->Map.of("name",a,"type","string","multiValued",a.equals("emails")||a.equals("members"))).toList());}
    private static Map<String,Object> resourceType(String name,String endpoint,String schema){return Map.of("schemas",List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"),"id",name,"name",name,"endpoint",endpoint,"schema",schema);}
    private static Map<String,Object> throwNotFound(){throw new com.axiom.common.NotFoundException("Unknown SCIM resource type");}
}
