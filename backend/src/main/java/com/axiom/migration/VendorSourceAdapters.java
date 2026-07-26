package com.axiom.migration;

import com.axiom.migration.SourceContract.SourceAdapter;
import com.axiom.migration.SourceContract.SourceField;
import com.axiom.migration.SourceContract.SourceHandshake;
import com.axiom.migration.SourceContract.SourceObject;
import com.axiom.migration.SourceContract.SourceRecord;
import com.axiom.migration.SourceContract.SourceSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;

/**
 * Salesforce, Zoho CRM and HubSpot behind the one anti-corruption contract
 * (FR-MIG-001, ADR-007).
 *
 * <h2>What is real here and what is not — stated plainly</h2>
 * The <b>contract</b> is real: each vendor is a distinct adapter, declares the
 * read-only OAuth scopes it would request, and publishes the object catalogue
 * its API exposes so an operator can plan a migration before any credential is
 * issued. That catalogue is the part of vendor knowledge that does not require a
 * live account, and it is what the mapping proposer needs.
 *
 * <p>The <b>live round-trip is DEFERRED</b>. Nobody on this build has a
 * Salesforce, Zoho or HubSpot tenant to authenticate against, and an adapter
 * that fakes an OAuth exchange it has never performed is worse than one that
 * refuses: it would pass CI, ship, and fail in front of a customer during the
 * one operation this module exists to make trustworthy. So
 * {@link SourceAdapter#liveInteropAvailable()} returns false and
 * {@link SourceAdapter#connect} throws {@link MigrationNotAvailableException},
 * which the API surfaces as 501 with the reason named.
 *
 * <p>{@link FixtureSourceAdapter} is the adapter that does complete the
 * round-trip, so the engine behind these three is exercised end to end today.
 * Finishing a vendor is then a matter of filling four methods against the
 * contract below, not of building the engine.
 */
@Configuration
public class VendorSourceAdapters {

    @Bean
    public SourceAdapter salesforceSourceAdapter() {
        return new DeferredVendorAdapter(
                "SALESFORCE", "Salesforce",
                // Salesforce's read-only pairing: api for the REST/Bulk surface,
                // refresh_token so a long migration survives token expiry.
                List.of("api", "refresh_token", "offline_access"),
                List.of(
                        catalogue("Account", "Account", false, "ACCOUNT"),
                        catalogue("Contact", "Contact", false, "CONTACT"),
                        catalogue("Lead", "Lead", false, "LEAD"),
                        catalogue("Opportunity", "Opportunity", false, "OPPORTUNITY"),
                        catalogue("OpportunityContactRole", "Opportunity Contact Role", false, "OPPORTUNITY_CONTACT_ROLE"),
                        catalogue("Task", "Task", false, "ACTIVITY"),
                        catalogue("Event", "Event", false, "ACTIVITY"),
                        catalogue("Note", "Note", false, "ACTIVITY"),
                        catalogue("Attachment", "Attachment", false, "ATTACHMENT")));
    }

    @Bean
    public SourceAdapter zohoSourceAdapter() {
        return new DeferredVendorAdapter(
                "ZOHO", "Zoho CRM",
                List.of("ZohoCRM.modules.ALL.READ", "ZohoCRM.settings.ALL.READ", "ZohoCRM.bulk.READ"),
                List.of(
                        catalogue("Accounts", "Accounts", false, "ACCOUNT"),
                        catalogue("Contacts", "Contacts", false, "CONTACT"),
                        catalogue("Leads", "Leads", false, "LEAD"),
                        catalogue("Deals", "Deals", false, "OPPORTUNITY"),
                        catalogue("Tasks", "Tasks", false, "ACTIVITY"),
                        catalogue("Calls", "Calls", false, "ACTIVITY"),
                        catalogue("Notes", "Notes", false, "ACTIVITY"),
                        catalogue("Attachments", "Attachments", false, "ATTACHMENT")));
    }

    @Bean
    public SourceAdapter hubspotSourceAdapter() {
        return new DeferredVendorAdapter(
                "HUBSPOT", "HubSpot",
                List.of("crm.objects.companies.read", "crm.objects.contacts.read",
                        "crm.objects.deals.read", "crm.schemas.custom.read", "crm.objects.custom.read"),
                List.of(
                        catalogue("companies", "Companies", false, "ACCOUNT"),
                        catalogue("contacts", "Contacts", false, "CONTACT"),
                        catalogue("deals", "Deals", false, "OPPORTUNITY"),
                        catalogue("engagements", "Engagements", false, "ACTIVITY"),
                        catalogue("notes", "Notes", false, "ACTIVITY"),
                        catalogue("files", "Files", false, "ATTACHMENT")));
    }

    private static SourceObject catalogue(String apiName, String label, boolean custom, String target) {
        // recordCount 0: a count is a fact about a live org, and this adapter has
        // not connected to one. Reporting a made-up number is the failure mode.
        return new SourceObject(apiName, label, custom, 0L, target);
    }

    /**
     * Publishes the vendor's shape; refuses to pretend it has authenticated.
     * Not an inner class of a service so the three beans above read as three
     * vendors rather than three flags.
     */
    static final class DeferredVendorAdapter implements SourceAdapter {

        private final String vendor;
        private final String displayName;
        private final List<String> scopes;
        private final List<SourceObject> catalogue;

        DeferredVendorAdapter(String vendor, String displayName, List<String> scopes, List<SourceObject> catalogue) {
            this.vendor = vendor;
            this.displayName = displayName;
            this.scopes = List.copyOf(scopes);
            this.catalogue = List.copyOf(catalogue);
        }

        @Override public String vendor() { return vendor; }

        @Override public String displayName() { return displayName; }

        @Override public boolean liveInteropAvailable() { return false; }

        @Override public List<String> requestedScopes() { return scopes; }

        @Override
        public SourceHandshake connect(SourceSession session) {
            throw new MigrationNotAvailableException(
                    displayName + " live interop is DEFERRED in this build. The adapter contract, its "
                    + "read-only scopes " + scopes + " and its object catalogue are implemented, but no "
                    + "authenticated round-trip has been performed against a " + displayName + " org, so "
                    + "connecting would be a claim this build cannot support. Use the local fixture source "
                    + "to exercise the migration engine end to end.");
        }

        /** The catalogue is planning information and is available without a connection. */
        @Override
        public List<SourceObject> objects(SourceSession session) {
            return catalogue;
        }

        @Override
        public List<SourceField> fields(SourceSession session, String objectApiName) {
            throw new MigrationNotAvailableException(
                    displayName + " field discovery requires a live connection, which is DEFERRED in this build. "
                    + "Custom fields cannot be enumerated from a static catalogue and this module will not guess at them.");
        }

        @Override
        public List<SourceRecord> records(SourceSession session, String objectApiName, Instant modifiedSince) {
            throw new MigrationNotAvailableException(
                    displayName + " record extraction is DEFERRED in this build.");
        }
    }
}
