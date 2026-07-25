package com.axiom.pipeline;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Value types for stage gate evaluation (FR-OPP-003). */
public final class StageGate {

    private StageGate() {}

    /** One configured criterion, belonging to exactly one published version. */
    public record Criterion(UUID id, String code, String label, String type,
                            String expressionJson, String message, String remediation, int sortOrder) {}

    /**
     * A criteria version. `versionNumber` is what STAGE_HISTORY pins, so that an
     * in-flight opportunity keeps being judged by the rules it entered under.
     */
    public record Version(UUID id, UUID stageId, String gate, int versionNumber, List<Criterion> criteria) {

        public static Version empty(UUID stageId, String gate) {
            return new Version(null, stageId, gate, 0, List.of());
        }
    }

    /**
     * An unsatisfied criterion: what is wrong AND what to do about it. FR-OPP-003
     * requires both — a refusal that only says "criteria not met" is the failure
     * mode this requirement exists to prevent.
     */
    public record Unsatisfied(String gate, String stageName, String code, String criterion,
                              String observation, String action) {}

    /**
     * Everything a criterion can be evaluated against, loaded once per gate
     * check. Kept as a flat record so unit tests can construct one directly
     * without a database.
     */
    public record Facts(UUID opportunityId,
                        String opportunityName,
                        BigDecimal amount,
                        LocalDate closeDate,
                        String nextStep,
                        BigDecimal recurringAmount,
                        Integer termMonths,
                        BigDecimal qualificationScore,
                        Set<String> contactRoles,
                        int lineCount,
                        int competitorCount,
                        Map<String, String> approvalStates,
                        Map<String, Integer> completedActivityCounts,
                        Map<String, Instant> lastCompletedActivityAt,
                        Instant now) {}

    /** Result of a gate evaluation: the pinned version applied and what failed. */
    public record Result(Version version, List<Unsatisfied> unsatisfied) {

        public boolean passed() {
            return unsatisfied.isEmpty();
        }
    }
}
