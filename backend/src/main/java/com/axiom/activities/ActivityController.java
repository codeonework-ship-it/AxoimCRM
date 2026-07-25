package com.axiom.activities;

import com.axiom.api.PageResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {
    private final ActivityService activities;

    public ActivityController(ActivityService activities) {
        this.activities = activities;
    }

    @GetMapping
    public PageResult<ActivityService.ActivityRow> list(@RequestParam(required = false) String search,
                                                        @RequestParam(required = false) String type,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String relatedEntityType,
                                                        @RequestParam(required = false) UUID relatedEntityId,
                                                        @RequestParam(defaultValue = "0") int page) {
        return activities.list(search, type, status, relatedEntityType, relatedEntityId, page);
    }

    @GetMapping("/summary")
    public ActivityService.ActivitySummary summary() {
        return activities.summary();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityService.ActivityRow create(@RequestBody @Valid ActivityService.ActivityRequest request) {
        return activities.create(request);
    }

    @PatchMapping("/{id}/complete")
    public ActivityService.ActivityRow complete(@PathVariable UUID id,
                                                @RequestBody ActivityService.CompleteRequest request) {
        return activities.complete(id, request);
    }
}
