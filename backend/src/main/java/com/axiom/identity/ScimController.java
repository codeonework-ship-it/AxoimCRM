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
 * <p>Group endpoints are not implemented. Saying so plainly is more useful than a
 * stub: FR-TEN-007 names group provisioning, and until E02's profile and
 * permission-set model exists there is nothing coherent to map a directory group
 * onto. The ServiceProviderConfig below advertises that honestly rather than
 * claiming support a connector would then fail against.
 */
@RestController
@RequestMapping(value = "/scim/v2", produces = "application/scim+json")
public class ScimController {

    private final ScimUserService users;

    public ScimController(ScimUserService users) {
        this.users = users;
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

    @GetMapping("/ServiceProviderConfig")
    public Map<String, Object> serviceProviderConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));
        config.put("documentationUri", "/docs/manual/user-guide.md#identity--access");
        config.put("patch", Map.of("supported", true));
        config.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
        config.put("filter", Map.of("supported", true, "maxResults", 200,
                "note", "Supports userName eq \"value\" and active eq true|false"));
        config.put("changePassword", Map.of("supported", false,
                "note", "SCIM-provisioned users authenticate through the directory, not with a local password"));
        config.put("sort", Map.of("supported", false));
        config.put("etag", Map.of("supported", false));
        config.put("authenticationSchemes", List.of(Map.of(
                "type", "oauthbearertoken",
                "name", "Axiom SCIM token",
                "description", "A workspace-scoped provisioning token issued from Sessions & Security")));
        config.put("resourceTypesSupported", List.of("User"));
        config.put("groupsSupported", false);
        config.put("note", "Group provisioning is not implemented: Axiom's profile and permission-set model "
                + "(epic E02) is not yet built, so there is nothing coherent to map a directory group onto. "
                + "Assign the Axiom role through the "
                + ScimUserService.AXIOM_EXTENSION + " extension on the user resource instead.");
        return config;
    }
}
