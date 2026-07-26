package com.axiom.activity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The user-activity admin surface.
 *
 * <p>Read-only by construction: this is evidence, and there is no endpoint that
 * edits or deletes it. The table refuses UPDATE and DELETE at the trigger and at
 * the grant, so the absence of a write endpoint here is a design statement
 * rather than an omission waiting to be filled in.
 */
/*
 * Explicit bean name. Spring derives "activityController" from the class name,
 * which collides with the pre-existing com.axiom.activities.ActivityController
 * (CRM activity timeline) and fails the whole context with a
 * ConflictingBeanDefinitionException. Different concerns, same class name —
 * this one is the user-activity audit trail, not CRM activities.
 */
@RestController("userActivityController")
@RequestMapping("/api/v1/activity")
public class ActivityController {

    private final UserActivityService activity;

    public ActivityController(UserActivityService activity) {
        this.activity = activity;
    }

    /** Filterable feed: user, action, object, outcome and date range. */
    @GetMapping("/events")
    public List<UserActivityService.ActivityRow> events(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) Integer limit) {
        return activity.search(new UserActivityService.ActivityQuery(
                actorId, action, objectType, outcome, from, to, limit));
    }

    /** Counts for the same filter, so "no rows" is distinguishable from "no data". */
    @GetMapping("/summary")
    public UserActivityService.ActivitySummary summary(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to) {
        return activity.summary(new UserActivityService.ActivityQuery(
                actorId, action, objectType, outcome, from, to, null));
    }

    /** Drill into one user's timeline. */
    @GetMapping("/users/{userId}/timeline")
    public UserActivityService.UserTimeline timeline(@PathVariable UUID userId,
                                                     @RequestParam(defaultValue = "200") int limit) {
        return activity.timeline(userId, limit);
    }

    @GetMapping("/actions")
    public List<String> actions() {
        return activity.knownActions();
    }

    /**
     * The FR-AUD-014 allowlist, published rather than asserted. An administrator
     * can see exactly which keys the log is permitted to hold.
     */
    @GetMapping("/detail-allowlist")
    public List<Map<String, Object>> detailAllowlist() {
        return activity.detailAllowlist();
    }
}
