package com.axiom.activity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-reported activity ingest.
 *
 * <h2>Why this is a separate controller from ActivityController</h2>
 * {@code ActivityController} is documented as read-only BY CONSTRUCTION — the
 * admin surface over the audit trail, with no endpoint that writes to it, which
 * is a deliberate statement about what that surface is for. Adding the one write
 * route to it would quietly falsify that comment for the next reader. The
 * capabilities are genuinely different: this endpoint appends observations and
 * can never read them back.
 *
 * <h2>Append-only, and unreadable from here</h2>
 * There is no GET on this controller. A client can report that it opened a
 * screen; it cannot ask what anyone else has reported. Reading the trail requires
 * the admin surface, which is gated separately.
 *
 * <p>The response is deliberately thin — a count of what was accepted — because a
 * client has no use for anything more and echoing the stored rows back would hand
 * the caller a read path through the write endpoint.
 */
@RestController
@RequestMapping("/api/v1/activity")
public class UiEventController {

    private final UiEventService service;

    public UiEventController(UiEventService service) {
        this.service = service;
    }

    /**
     * One reported event. {@code screen} is the client route; {@code ageMs} is how
     * long ago the client thinks it happened, which matters because these arrive
     * batched and the server timestamps them on receipt.
     */
    public record UiEventRequest(
            @Size(max = 64) String action,
            @Size(max = 300) String screen,
            @Size(max = 64) String objectType,
            UUID objectId,
            Integer ageMs) {}

    /**
     * {@code @Size} on the list is the batch cap, enforced as a 400 rather than by
     * silently truncating. A client sending 500 events has a bug, and trimming to
     * 50 would hide it while losing 450 rows.
     */
    public record UiEventBatch(
            @NotEmpty @Size(max = UiEventService.MAX_BATCH) List<UiEventRequest> events) {}

    @PostMapping("/ui-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> ingest(@Valid @RequestBody UiEventBatch batch,
                                      HttpServletRequest request) {
        List<UiEventService.UiEvent> events = batch.events().stream()
                .map(e -> new UiEventService.UiEvent(
                        e.action(), e.screen(), e.objectType(), e.objectId(), e.ageMs()))
                .toList();

        int accepted = service.record(events, clientIp(request), request.getHeader("User-Agent"));

        /*
         * Reporting both numbers, because they can legitimately differ: an action
         * this build does not recognise is skipped rather than failing the batch,
         * so a client one release ahead can see that some of what it sent was not
         * understood instead of assuming everything landed.
         */
        return Map.of("received", events.size(), "accepted", accepted);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }
}
