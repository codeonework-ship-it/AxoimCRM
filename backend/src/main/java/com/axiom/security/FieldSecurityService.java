package com.axiom.security;

import com.axiom.api.PageResult;
import com.axiom.common.ForbiddenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Field-level security (FR-SEC-007), enforced at serialization — one enforcement
 * point, not one per surface.
 *
 * <h2>Absence, not null</h2>
 * A field the caller cannot read is <b>removed from the map</b>. It is not set to
 * null, not set to an empty string, and not replaced with a placeholder. The
 * requirement says this outright ("absence and emptiness must not be conflated")
 * and the reason is concrete: for a field like {@code annualRevenue}, {@code null}
 * means "we don't know this company's revenue" while absence means "you are not
 * cleared to see it". A rep sizing an account acts differently on those two, and a
 * downstream report that receives null cannot tell them apart at all.
 *
 * <h2>Why services still return records</h2>
 * DTOs stay as java records — they are the contract, and they are what the compiler
 * checks. The projection to a map happens once, here, on the way out. That keeps the
 * "exactly one enforcement point" property of the model document (§4) while leaving
 * the query layer typed.
 *
 * <h2>Default readability</h2>
 * A field with no {@code field_permission} row is readable. This is not a hole in
 * deny-by-default — object and record access are already denied by default, so a
 * caller only reaches this class for a record they are entitled to see. Making
 * fields deny-by-default instead would mean a newly added column is invisible to
 * every profile until an administrator enumerates it, which in practice produces
 * blank screens after every release rather than better security.
 */
@Service
public class FieldSecurityService {

    private final PermissionResolver resolver;
    private final SensitiveFieldService sensitiveFields;
    private final ObjectMapper json;

    public FieldSecurityService(PermissionResolver resolver, SensitiveFieldService sensitiveFields,
                                ObjectMapper json) {
        this.resolver = resolver;
        this.sensitiveFields = sensitiveFields;
        this.json = json;
    }

    /** Project one DTO. Prefer the collection overloads for lists — they resolve the context once. */
    @Transactional(readOnly = true)
    public Map<String, Object> project(SecurableObject object, Object dto) {
        AccessContext ctx = resolver.current();
        return project(ctx, object, dto, sensitiveFields.registry(ctx.tenantId(), object));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> projectAll(SecurableObject object, List<?> dtos) {
        AccessContext ctx = resolver.current();
        Map<String, SensitiveFieldService.SensitiveField> sensitive =
                sensitiveFields.registry(ctx.tenantId(), object);
        List<Map<String, Object>> out = new ArrayList<>(dtos.size());
        for (Object dto : dtos) out.add(project(ctx, object, dto, sensitive));
        return out;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> projectPage(SecurableObject object, PageResult<?> page) {
        return new PageResult<>(projectAll(object, page.items()), page.page(), page.size(),
                page.total(), page.totalPages());
    }

    private Map<String, Object> project(AccessContext ctx, SecurableObject object, Object dto,
                                        Map<String, SensitiveFieldService.SensitiveField> sensitive) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = json.convertValue(dto, LinkedHashMap.class);
        Set<String> hidden = ctx.unreadable(object);
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (hidden.contains(key) && !object.alwaysReadable(key)) return; // ABSENT, not null
            SensitiveFieldService.SensitiveField field = sensitive.get(key);
            if (field != null && value != null && !sensitiveFields.canReveal(ctx, object, field)) {
                out.put(key, sensitiveFields.mask(field.maskStyle(), value.toString()));
            } else {
                out.put(key, value);
            }
        });
        return out;
    }

    /**
     * Field names the caller may not read on this object — surfaced to the UI so it
     * can render "restricted" rather than an empty column, and consumed by the
     * access explainer.
     */
    @Transactional(readOnly = true)
    public Set<String> unreadableFields(SecurableObject object) {
        return resolver.current().unreadable(object);
    }

    /**
     * Refuses a write that touches a field the caller may not edit. US-E02-04
     * requires this "via any interface including bulk update and automation
     * triggered by them", so every mutate path calls it rather than each one
     * re-deriving the rule.
     */
    @Transactional(readOnly = true)
    public void requireEditable(SecurableObject object, Collection<String> fields) {
        AccessContext ctx = resolver.current();
        Set<String> hidden = ctx.unreadable(object);
        Set<String> locked = ctx.readOnly(object);
        List<String> refused = new ArrayList<>();
        for (String field : fields) {
            if (hidden.contains(field) || locked.contains(field)) refused.add(field);
        }
        if (!refused.isEmpty()) {
            throw new ForbiddenException("Your profile does not permit changing "
                    + String.join(", ", refused) + " on a " + object.name().toLowerCase(java.util.Locale.ROOT)
                    + ". Remove those fields from the request or ask an administrator for edit access.");
        }
    }
}
