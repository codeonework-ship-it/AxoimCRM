package com.axiom.migration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shapes the migration engine passes between its own parts, and returns to
 * the API.
 *
 * <p>Gathered in one file because they are one vocabulary — a plan produces a
 * report, a report is made of object outcomes and issues, and an issue is the
 * same shape whether it came from the dry run, the import or the rollback.
 * Splitting them across nine files would hide that.
 */
public final class MigrationModel {

    private MigrationModel() {}

    // ------------------------------------------------------------------ plan

    /**
     * A plan resolved into something executable: one entry per source object,
     * with the mapping already reduced to source-field to target-field, plus the
     * fields that will NOT come across.
     *
     * @param unmappedFields source fields with no Axiom destination. Carried on the
     *                       executable plan itself, not looked up later, so the
     *                       dry-run report cannot be produced without them.
     */
    public record ObjectPlan(String sourceObject,
                             String targetEntity,
                             Map<String, String> mappedFields,
                             List<String> unmappedFields,
                             List<String> ignoredFields,
                             Map<String, String> references,
                             List<String> moneyFields) {}

    public record PlanContext(UUID planId,
                              String planName,
                              UUID connectionId,
                              String vendor,
                              boolean sampleData,
                              Instant deltaWatermark,
                              List<ObjectPlan> objects) {}

    // ------------------------------------------------------------------ issues

    /**
     * One reason something did not land, or landed differently than the source
     * had it.
     *
     * <p>{@code relatedObject}/{@code relatedRecordId}/{@code relatedLabel} are
     * the FR-MIG-004 clause: a relationship that cannot be resolved names BOTH
     * endpoints. They are on the general issue type rather than a special
     * referential-gap type so that no code path can report a broken link without
     * having somewhere to put the other end.
     */
    public record Issue(String severity,
                        String category,
                        String sourceObject,
                        String sourceRecordId,
                        String sourceLabel,
                        String fieldName,
                        String relatedObject,
                        String relatedRecordId,
                        String relatedLabel,
                        String reason) {

        public static Issue validation(String object, String id, String label, String field, String reason) {
            return new Issue("ERROR", "VALIDATION", object, id, label, field, null, null, null, reason);
        }

        public static Issue duplicate(String object, String id, String label, String field,
                                      String existingId, String existingLabel, String reason) {
            return new Issue("ERROR", "DUPLICATE", object, id, label, field,
                    "AXIOM", existingId, existingLabel, reason);
        }

        /** Both endpoints, always. */
        public static Issue referentialGap(String severity, String object, String id, String label, String field,
                                           String relatedObject, String relatedId, String reason) {
            return new Issue(severity, "REFERENTIAL_GAP", object, id, label, field,
                    relatedObject, relatedId, null, reason);
        }

        public static Issue unmappedField(String object, String field, String reason) {
            return new Issue("WARNING", "UNMAPPED_FIELD", object, null, null, field, null, null, null, reason);
        }

        public static Issue skipped(String object, String id, String label, String reason) {
            return new Issue("WARNING", "SKIPPED", object, id, label, null, null, null, null, reason);
        }

        public static Issue info(String category, String object, String id, String label, String reason) {
            return new Issue("INFO", category, object, id, label, null, null, null, null, reason);
        }
    }

    // ------------------------------------------------------------------ reports

    /**
     * Per-object outcome. Identical shape for a dry run and a real run — the dry
     * run's numbers are a prediction produced by the same validation the import
     * applies, so a report that changes between the two is a bug, not a surprise.
     */
    public record ObjectOutcome(String sourceObject,
                                String targetEntity,
                                long sourceCount,
                                long toCreate,
                                long toUpdate,
                                long toSkip,
                                BigDecimal sourceAmountSum,
                                List<String> unmappedFields) {}

    /**
     * The FR-MIG-003 pre-flight report. Everything the operator needs to decide
     * whether to write, and nothing written to produce it.
     */
    public record PreFlightReport(UUID planId,
                                  String planName,
                                  Instant generatedAt,
                                  Instant deltaSince,
                                  List<ObjectOutcome> objects,
                                  List<Issue> issues,
                                  List<String> unmappedFields,
                                  long totalToCreate,
                                  long totalToUpdate,
                                  long totalToSkip,
                                  long validationFailures,
                                  long duplicates,
                                  long referentialGaps) {}

    /** One line of the FR-MIG-006 reconciliation report. */
    public record ReconciliationLine(String sourceObject,
                                     String targetEntity,
                                     long sourceCount,
                                     long targetCount,
                                     long notMigratedCount,
                                     BigDecimal sourceAmountSum,
                                     BigDecimal targetAmountSum,
                                     String currencyCode,
                                     boolean balanced) {}

    public record ReconciliationReport(UUID runId,
                                       UUID planId,
                                       String planName,
                                       Instant generatedAt,
                                       List<ReconciliationLine> lines,
                                       List<Issue> notMigrated,
                                       boolean balanced) {}

    // ------------------------------------------------------------------ rollback

    /**
     * What a rollback would remove, before it removes anything.
     *
     * @param modifiedSinceMigration records the migration created that a user has
     *                               since edited. 13-integration-and-migration.md
     *                               §3.6: flagged in the preview so "the operator
     *                               decides with full information, not after the fact".
     */
    public record RollbackPreview(UUID planId,
                                  String planName,
                                  Instant importedAt,
                                  int retentionDays,
                                  Instant retentionExpiresAt,
                                  boolean withinRetention,
                                  Map<String, Long> countsByEntity,
                                  long totalRecords,
                                  List<Issue> modifiedSinceMigration,
                                  List<PreExistingGuard> untouched) {}

    /**
     * The other half of "removes exactly what it created": what rollback will
     * NOT touch. Stating the surviving count up front turns an invisible
     * guarantee into something the operator can check against the screen they
     * are already looking at.
     */
    public record PreExistingGuard(String entity, long preExistingRecords) {}

    // ------------------------------------------------------------------ job handle

    /**
     * What a long operation returns immediately (system-design §3.3). A migration
     * is episodic and resource-hungry and must never be a held HTTP request.
     */
    public record RunHandle(UUID runId,
                            UUID planId,
                            String mode,
                            String status,
                            String phase,
                            long totalUnits,
                            long processedUnits,
                            int percentComplete,
                            long recordsCreated,
                            long recordsUpdated,
                            long recordsSkipped,
                            long recordsRemoved,
                            long issueCount,
                            Instant queuedAt,
                            Instant startedAt,
                            Instant finishedAt,
                            String message) {}
}
