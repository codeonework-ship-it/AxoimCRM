package com.axiom.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * List-view management: saved views, and the bulk operations run against a
 * selection made in one.
 *
 * <p>They share a controller because they are one workflow — shape the list,
 * then act on what it selected — and splitting them would put two halves of the
 * same user story behind two unrelated paths.
 */
@RestController
@RequestMapping("/api/v1/list-views")
public class ListViewController {

    private final SavedViewService views;
    private final BulkOperationService bulk;

    public ListViewController(SavedViewService views, BulkOperationService bulk) {
        this.views = views;
        this.bulk = bulk;
    }

    // ------------------------------------------------------------ saved views

    @GetMapping
    public List<SavedViewService.SavedView> list(@RequestParam String gridKey) {
        return views.list(gridKey);
    }

    @GetMapping("/{id}")
    public SavedViewService.SavedView get(@PathVariable UUID id) {
        return views.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedViewService.SavedView create(@RequestBody @Valid SavedViewService.SavedViewRequest request) {
        return views.create(request);
    }

    /** Version in the query string for the same reason contacts do it: a missing
     *  required parameter is a 400, a missing body field would be a silent null. */
    @PutMapping("/{id}")
    public SavedViewService.SavedView update(@PathVariable UUID id,
                                             @RequestParam long version,
                                             @RequestBody @Valid SavedViewService.SavedViewRequest request) {
        return views.update(id, version, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        views.delete(id);
    }

    // -------------------------------------------------------- bulk operations

    /**
     * Published so the UI offers exactly the fields the server will accept.
     * A picker built from a hard-coded frontend list drifts the moment the
     * allow-list changes, and the user discovers it as a 400 on save.
     */
    @GetMapping("/bulk/editable-fields")
    public Map<String, List<String>> editableFields() {
        return bulk.editableFields();
    }

    @PostMapping("/bulk/{objectType}/field")
    public BulkOperationService.BulkResult updateField(
            @PathVariable String objectType,
            @RequestBody @Valid BulkOperationService.BulkFieldUpdate request) {
        return bulk.updateField(objectType, request);
    }

    @PostMapping("/bulk/{objectType}/reassign")
    public BulkOperationService.BulkResult reassign(
            @PathVariable String objectType,
            @RequestBody @Valid BulkOperationService.BulkReassign request) {
        return bulk.reassign(objectType, request);
    }
}
