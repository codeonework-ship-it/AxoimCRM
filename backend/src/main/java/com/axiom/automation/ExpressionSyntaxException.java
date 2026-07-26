package com.axiom.automation;

/**
 * A formula that cannot be parsed, with the character position that stopped the
 * parser (FR-AUT-009).
 *
 * <p>The position is 1-based and counted in characters, because that is what an
 * editor caret shows. A syntax checker that says only "invalid formula" makes
 * the administrator bisect their own expression by hand, which is the failure
 * mode the requirement exists to prevent.
 */
public class ExpressionSyntaxException extends RuntimeException {

    private final int position;
    private final String expression;

    public ExpressionSyntaxException(String message, String expression, int position) {
        super(message);
        this.expression = expression;
        this.position = position;
    }

    /** 1-based character offset into the expression. */
    public int position() {
        return position;
    }

    public String expression() {
        return expression;
    }

    /** {@code amount > * 3} → {@code "          ^"} — the caret line an editor renders under the formula. */
    public String pointer() {
        int caret = Math.max(1, position);
        return " ".repeat(caret - 1) + "^";
    }
}
