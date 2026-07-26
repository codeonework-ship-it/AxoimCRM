package com.axiom.security;

import com.axiom.common.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Application service that turns high-risk RBAC grants into executable
 * maker-checker requests. Approval and application share one transaction: a
 * failed grant cannot leave an APPROVED request whose change never happened.
 */
@Service
public class RbacChangeApprovalService {

    public static final String ACTION = "PERMISSION_GRANT";

    private final MakerCheckerService approvals;
    private final PermissionAdminService permissions;
    private final RoleHierarchyService roles;
    private final AuthorizationService authorization;
    private final ObjectMapper json;

    public RbacChangeApprovalService(MakerCheckerService approvals, PermissionAdminService permissions,
                                     RoleHierarchyService roles, AuthorizationService authorization,
                                     ObjectMapper json) {
        this.approvals = approvals;
        this.permissions = permissions;
        this.roles = roles;
        this.authorization = authorization;
        this.json = json;
    }

    @Transactional
    public MakerCheckerService.ApprovalRequest submitRole(UUID userId, UUID roleNodeId, Instant expiresAt) {
        Map<String, Object> payload = payload("ROLE_ASSIGNMENT", userId);
        payload.put("roleNodeId", roleNodeId.toString());
        payload.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        return submit(userId, "Assign role " + roleNodeId + " to user " + userId, payload);
    }

    @Transactional
    public MakerCheckerService.ApprovalRequest submitProfile(UUID userId, UUID profileId, String reason) {
        Map<String, Object> payload = payload("PROFILE_ASSIGNMENT", userId);
        payload.put("profileId", profileId.toString());
        payload.put("reason", reason);
        return submit(userId, "Assign profile " + profileId + " to user " + userId, payload);
    }

    @Transactional
    public MakerCheckerService.ApprovalRequest submitPermissionAssignment(
            PermissionAdminService.AssignRequest request) {
        Map<String, Object> payload = payload("PERMISSION_ASSIGNMENT", request.userId());
        payload.put("permissionSetId", string(request.permissionSetId()));
        payload.put("permissionSetGroupId", string(request.permissionSetGroupId()));
        payload.put("expiresAt", request.expiresAt() == null ? null : request.expiresAt().toString());
        payload.put("reason", request.reason());
        String grant = request.permissionSetId() != null
                ? "permission set " + request.permissionSetId()
                : "permission-set group " + request.permissionSetGroupId();
        return submit(request.userId(), "Assign " + grant + " to user " + request.userId(), payload);
    }

    private MakerCheckerService.ApprovalRequest submit(UUID userId, String summary, Map<String, Object> payload) {
        RbacAccess.requireWrite("submit an RBAC grant for approval");
        return approvals.submit(new MakerCheckerService.SubmitRequest(
                ACTION, "APP_USER", userId, summary, payload));
    }

    @Transactional
    public MakerCheckerService.ApprovalRequest approveAndApply(UUID requestId, String note) {
        authorization.requirePermission("SYS.APPROVE_PERMISSION_GRANT", "approve a permission grant");
        MakerCheckerService.ApprovalRequest request = approvals.find(requestId);
        if (!ACTION.equals(request.actionCode())) {
            throw new ConflictException("This approval is not an RBAC permission grant and cannot be applied by the RBAC executor.");
        }
        Map<String, Object> payload = parse(request.payload());
        String changeType = required(payload, "changeType");

        // The four-eyes/delegation check and APPROVED transition happen before
        // the mutation, inside this same transaction. Any service refusal rolls
        // both back, leaving the request pending and truthful.
        MakerCheckerService.ApprovalRequest approved = approvals.approve(requestId, note);
        switch (changeType) {
            case "ROLE_ASSIGNMENT" -> roles.assignUser(uuid(payload, "userId"),
                    uuid(payload, "roleNodeId"), instant(payload.get("expiresAt")));
            case "PROFILE_ASSIGNMENT" -> permissions.assignProfile(uuid(payload, "userId"),
                    uuid(payload, "profileId"), optional(payload, "reason"));
            case "PERMISSION_ASSIGNMENT" -> permissions.assign(new PermissionAdminService.AssignRequest(
                    uuid(payload, "userId"), optionalUuid(payload, "permissionSetId"),
                    optionalUuid(payload, "permissionSetGroupId"), instant(payload.get("expiresAt")),
                    optional(payload, "reason")));
            default -> throw new ConflictException("Unsupported RBAC approval payload type '" + changeType + "'.");
        }
        return approved;
    }

    @Transactional
    public MakerCheckerService.ApprovalRequest reject(UUID requestId, String note) {
        authorization.requirePermission("SYS.APPROVE_PERMISSION_GRANT", "reject a permission grant");
        return approvals.reject(requestId, note);
    }

    private static Map<String, Object> payload(String changeType, UUID userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changeType", changeType);
        payload.put("userId", userId.toString());
        return payload;
    }

    private Map<String, Object> parse(String payload) {
        try {
            return json.readValue(payload, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new ConflictException("The approval payload is invalid and cannot be applied.");
        }
    }

    private static String required(Map<String, Object> payload, String key) {
        String value = optional(payload, key);
        if (value == null || value.isBlank()) throw new ConflictException("Approval payload is missing " + key + ".");
        return value;
    }

    private static String optional(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static UUID uuid(Map<String, Object> payload, String key) {
        return UUID.fromString(required(payload, key));
    }

    private static UUID optionalUuid(Map<String, Object> payload, String key) {
        String value = optional(payload, key);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static Instant instant(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : Instant.parse(String.valueOf(value));
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }
}
