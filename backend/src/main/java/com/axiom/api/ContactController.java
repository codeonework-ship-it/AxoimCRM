package com.axiom.api;

import com.axiom.accounts.ContactService;
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
import java.util.UUID;

/**
 * Contact authoring, at the depth accounts and leads already had.
 *
 * <p>{@link ContactService} has carried create, update, addresses, channels,
 * optimistic locking and duplicate guarding since V40 — but nothing exposed it.
 * The only route on this path was a list, so every one of those code paths was
 * unreachable over HTTP: written, tested by nothing, and dead. This controller is
 * mostly a wiring job, which is why it is thin.
 *
 * <p>The list endpoint still delegates to {@link QueryService} for its lightweight
 * projection when callers ask for the old shape, but the grid uses
 * {@code /contacts/full} — the difference is that the full row carries the
 * {@code version} an editor needs. Handing the UI a row it cannot safely PUT back
 * is how optimistic locking degrades into last-write-wins.
 *
 * <p>Write authorization is not repeated here. {@code JwtAuthFilter} refuses every
 * mutating method for a read-only audit role before a controller is reached, which
 * is the same gate {@code AccountController} relies on; adding a second per-method
 * check in this package only would suggest the filter is not trusted.
 */
@RestController
@RequestMapping("/api/v1/contacts")
public class ContactController {

    private final QueryService queries;
    private final ContactService contacts;

    public ContactController(QueryService queries, ContactService contacts) {
        this.queries = queries;
        this.contacts = contacts;
    }

    // ---------------------------------------------------------------- reading

    /** The original lightweight projection. Kept because existing callers bind to it. */
    @GetMapping
    public List<QueryService.ContactRow> list(@RequestParam(required = false) UUID accountId) {
        return queries.listContacts(accountId);
    }

    /** The grid projection: same rows, plus the version and audit fields an editor needs. */
    @GetMapping("/full")
    public List<ContactService.ContactDetail> listFull(@RequestParam(required = false) UUID accountId,
                                                       @RequestParam(required = false) String search,
                                                       @RequestParam(required = false) String status) {
        return contacts.list(accountId, search, status);
    }

    @GetMapping("/{id}")
    public ContactService.ContactDetail get(@PathVariable UUID id) {
        return contacts.get(id);
    }

    /** Contact, direct reports, engagement timeline, addresses and channels in one read. */
    @GetMapping("/{id}/view")
    public ContactService.ContactView view(@PathVariable UUID id) {
        return contacts.view(id);
    }

    @GetMapping("/{id}/addresses")
    public List<ContactService.AddressRow> addresses(@PathVariable UUID id) {
        return contacts.addresses("CONTACT", id);
    }

    @GetMapping("/{id}/channels")
    public List<ContactService.ChannelRow> channels(@PathVariable UUID id) {
        return contacts.channels(id);
    }

    // ---------------------------------------------------------------- writing

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContactService.ContactDetail create(@RequestBody @Valid ContactService.ContactRequest request) {
        return contacts.create(request);
    }

    /**
     * The version comes from the query string rather than the body so that a
     * caller cannot omit it and silently get last-write-wins. A missing required
     * parameter is a 400; a missing body field would have been a null.
     */
    @PutMapping("/{id}")
    public ContactService.ContactDetail update(@PathVariable UUID id,
                                               @RequestParam long version,
                                               @RequestBody @Valid ContactService.ContactRequest request) {
        return contacts.update(id, version, request);
    }

    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public ContactService.ContactDetail clone(@PathVariable UUID id,
                                              @RequestBody(required = false) ContactService.ContactRequest overrides) {
        return contacts.clone(id, overrides);
    }

    @PostMapping("/{id}/reassign")
    public ContactService.ContactDetail reassign(@PathVariable UUID id,
                                                 @RequestBody @Valid ContactService.ReassignRequest request) {
        return contacts.reassign(id, request.ownerId(), request.reason());
    }

    @PostMapping("/{id}/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public ContactService.AddressRow addAddress(@PathVariable UUID id,
                                                @RequestBody @Valid ContactService.AddressRequest request) {
        return contacts.addAddress(new ContactService.AddressRequest(
                "CONTACT", id, request.addressType(), request.isPrimary(), request.line1(),
                request.line2(), request.city(), request.stateRegion(), request.postalCode(),
                request.countryCode()));
    }

    @PostMapping("/{id}/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ContactService.ChannelRow> addChannel(@PathVariable UUID id,
                                                      @RequestBody @Valid ContactService.ChannelRequest request) {
        return contacts.addChannel(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        contacts.delete(id, reason);
    }
}
