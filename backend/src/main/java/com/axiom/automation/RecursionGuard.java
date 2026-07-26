package com.axiom.automation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Loop and recursion protection (FR-AUT-012).
 *
 * <h2>The diagnostic is the requirement</h2>
 * FR-AUT-012 does not merely ask that cascades be halted; it asks that the halt
 * <em>name the participating rules and the cycle</em>. A depth counter alone
 * satisfies the first half and leaves an administrator with "automation stopped"
 * and no way to find which two rules are feeding each other. So the guard keeps
 * the full activation path, not a count, and the message it produces reads
 * {@code A → B → A} with both rule names spelled out.
 *
 * <h2>Why a thread-local</h2>
 * A cascade is, by construction, one call stack inside one transaction: rule A
 * writes a field, the write dispatches rule B synchronously, B writes back. The
 * activation path is therefore exactly the thread's path, and holding it on the
 * thread means a second request cascading over the same record at the same time
 * cannot mistake the other's frames for its own.
 */
public final class RecursionGuard {

    /** One activation: this rule, running against this record. */
    public record Frame(String ruleCode, String ruleName, String objectType, UUID recordId) {}

    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private RecursionGuard() {}

    /**
     * @return a diagnostic naming the cycle if entering this frame would re-enter
     *         a rule already active on the same record, otherwise empty
     */
    public static java.util.Optional<String> cycleIfEntered(Frame frame) {
        List<Frame> path = path();
        int at = -1;
        for (int i = 0; i < path.size(); i++) {
            Frame f = path.get(i);
            if (f.ruleCode().equals(frame.ruleCode())
                    && f.objectType().equals(frame.objectType())
                    && java.util.Objects.equals(f.recordId(), frame.recordId())) {
                at = i;
                break;
            }
        }
        if (at < 0) return java.util.Optional.empty();

        List<Frame> cycle = new ArrayList<>(path.subList(at, path.size()));
        cycle.add(frame);
        String arrow = cycle.stream().map(Frame::ruleCode).collect(Collectors.joining(" → "));
        Set<String> participants = new LinkedHashSet<>();
        cycle.forEach(f -> participants.add(f.ruleCode() + " (" + f.ruleName() + ")"));
        return java.util.Optional.of(
                "Automation halted: a recursive cascade was detected on " + frame.objectType()
                        + " " + frame.recordId() + ". Cycle: " + arrow
                        + ". Participating rules: " + String.join(", ", participants)
                        + ". Nothing further was executed; break the cycle by narrowing an entry "
                        + "condition so the two rules cannot re-trigger each other.");
    }

    public static void push(Frame frame) {
        STACK.get().addLast(frame);
    }

    public static void pop() {
        Deque<Frame> stack = STACK.get();
        if (!stack.isEmpty()) stack.removeLast();
        if (stack.isEmpty()) STACK.remove();
    }

    public static int depth() {
        return STACK.get().size();
    }

    /** Oldest activation first — the order the diagnostic reads in. */
    public static List<Frame> path() {
        return new ArrayList<>(STACK.get());
    }

    /** Belt and braces for a thread handed back to the container mid-cascade. */
    public static void clear() {
        STACK.remove();
    }

    /** Rendered path, for a trace line that is not itself a cycle. */
    public static String describePath() {
        List<Frame> path = path();
        if (path.isEmpty()) return "(root)";
        return path.stream().map(Frame::ruleCode).collect(Collectors.joining(" → "));
    }
}
