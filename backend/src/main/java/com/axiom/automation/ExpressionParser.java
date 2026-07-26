package com.axiom.automation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recursive-descent parser for the Axiom formula language (FR-AUT-009).
 *
 * <h2>Why a parser and not a scripting engine</h2>
 * A formula written by a tenant administrator is evaluated server-side against
 * tenant data. Handing that string to a general scripting engine would hand the
 * administrator the JVM. This grammar has no assignment, no loops, no member
 * access beyond a two-part record reference, and no way to name a class — the
 * only things it can reach are the values the evaluator puts in front of it.
 *
 * <h2>Grammar</h2>
 * <pre>
 *   expression  := or
 *   or          := and      ( ("OR"  | "||") and )*
 *   and         := unaryNot ( ("AND" | "&amp;&amp;") unaryNot )*
 *   unaryNot    := ("NOT" | "!") unaryNot | comparison
 *   comparison  := additive ( ("=" | "==" | "!=" | "&lt;&gt;" | "&lt;" | "&lt;=" | "&gt;" | "&gt;=") additive )?
 *   additive    := multiplicative ( ("+" | "-") multiplicative )*
 *   multiplicative := unary ( ("*" | "/" | "%") unary )*
 *   unary       := "-" unary | primary
 *   primary     := NUMBER | STRING | TRUE | FALSE | NULL
 *                | IDENT "(" [ expression ("," expression)* ] ")"
 *                | IDENT ("." IDENT)?
 *                | "(" expression ")"
 * </pre>
 *
 * <p>Comparison is deliberately non-associative: {@code a < b < c} is a mistake
 * in every formula language that allows it, so it is a syntax error here.
 */
public final class ExpressionParser {

    // ------------------------------------------------------------------ AST

    public sealed interface Node permits Literal, Reference, FunctionCall, Unary, Binary {}

    /** A constant. {@code value} is already the runtime type: String, BigDecimal, Boolean or null. */
    public record Literal(Object value, int position) implements Node {}

    /**
     * {@code NEW.amount}, {@code OLD.amount} or a bare {@code amount}.
     *
     * @param scope {@code NEW}, {@code OLD} or {@code null} for an unqualified field
     */
    public record Reference(String scope, String field, int position) implements Node {}

    public record FunctionCall(String name, List<Node> arguments, int position) implements Node {}

    public record Unary(String operator, Node operand, int position) implements Node {}

    public record Binary(String operator, Node left, Node right, int position) implements Node {}

    // ------------------------------------------------------------------ tokens

    private enum Kind { NUMBER, STRING, IDENT, OPERATOR, LPAREN, RPAREN, COMMA, DOT, END }

    private record Token(Kind kind, String text, int position) {}

    private final String source;
    private final List<Token> tokens;
    private int index;

    private ExpressionParser(String source) {
        this.source = source;
        this.tokens = tokenize(source);
    }

    /**
     * @throws ExpressionSyntaxException with a 1-based character position
     */
    public static Node parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ExpressionSyntaxException("The formula is empty.", expression == null ? "" : expression, 1);
        }
        ExpressionParser parser = new ExpressionParser(expression);
        Node node = parser.expression();
        Token next = parser.peek();
        if (next.kind() != Kind.END) {
            throw parser.error("Unexpected '" + next.text() + "' after a complete expression.", next.position());
        }
        return node;
    }

    // ------------------------------------------------------------------ lexer

    private static final String OPERATOR_CHARS = "+-*/%<>=!&|";

    private List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int start = i;
            if (Character.isDigit(c)) {
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                String text = s.substring(start, i);
                if (text.chars().filter(ch -> ch == '.').count() > 1) {
                    throw error("'" + text + "' is not a valid number.", start + 1);
                }
                out.add(new Token(Kind.NUMBER, text, start + 1));
                continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    // '' inside a single-quoted literal is an escaped quote, the SQL
                    // convention — which is also what a JSON-embedded formula needs.
                    if (d == quote && i + 1 < s.length() && s.charAt(i + 1) == quote) {
                        sb.append(quote); i += 2; continue;
                    }
                    if (d == quote) { closed = true; i++; break; }
                    sb.append(d); i++;
                }
                if (!closed) throw error("Unterminated text literal.", start + 1);
                out.add(new Token(Kind.STRING, sb.toString(), start + 1));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                out.add(new Token(Kind.IDENT, s.substring(start, i), start + 1));
                continue;
            }
            switch (c) {
                case '(' -> { out.add(new Token(Kind.LPAREN, "(", start + 1)); i++; continue; }
                case ')' -> { out.add(new Token(Kind.RPAREN, ")", start + 1)); i++; continue; }
                case ',' -> { out.add(new Token(Kind.COMMA, ",", start + 1)); i++; continue; }
                case '.' -> { out.add(new Token(Kind.DOT, ".", start + 1)); i++; continue; }
                default -> { /* fall through to the operator scan */ }
            }
            if (OPERATOR_CHARS.indexOf(c) >= 0) {
                while (i < s.length() && OPERATOR_CHARS.indexOf(s.charAt(i)) >= 0) i++;
                String op = s.substring(start, i);
                if (!isKnownOperator(op)) {
                    throw error("'" + op + "' is not an operator this language recognises.", start + 1);
                }
                out.add(new Token(Kind.OPERATOR, op, start + 1));
                continue;
            }
            throw error("Unexpected character '" + c + "'.", start + 1);
        }
        out.add(new Token(Kind.END, "", s.length() + 1));
        return out;
    }

    private static boolean isKnownOperator(String op) {
        return switch (op) {
            case "+", "-", "*", "/", "%", "=", "==", "!=", "<>", "<", "<=", ">", ">=", "&&", "||", "!" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ parser

    private Token peek() { return tokens.get(index); }

    private Token next() { return tokens.get(index++); }

    private boolean matchOperator(String... ops) {
        Token t = peek();
        if (t.kind() != Kind.OPERATOR) return false;
        for (String op : ops) if (op.equals(t.text())) return true;
        return false;
    }

    private boolean matchKeyword(String keyword) {
        Token t = peek();
        return t.kind() == Kind.IDENT && t.text().equalsIgnoreCase(keyword);
    }

    private Node expression() { return or(); }

    private Node or() {
        Node left = and();
        while (matchKeyword("OR") || matchOperator("||")) {
            Token op = next();
            left = new Binary("OR", left, and(), op.position());
        }
        return left;
    }

    private Node and() {
        Node left = unaryNot();
        while (matchKeyword("AND") || matchOperator("&&")) {
            Token op = next();
            left = new Binary("AND", left, unaryNot(), op.position());
        }
        return left;
    }

    private Node unaryNot() {
        if (matchKeyword("NOT") || matchOperator("!")) {
            Token op = next();
            return new Unary("NOT", unaryNot(), op.position());
        }
        return comparison();
    }

    private Node comparison() {
        Node left = additive();
        if (matchOperator("=", "==", "!=", "<>", "<", "<=", ">", ">=")) {
            Token op = next();
            Node right = additive();
            if (matchOperator("=", "==", "!=", "<>", "<", "<=", ">", ">=")) {
                throw error("Comparisons cannot be chained. Write 'a > b AND b > c' instead.",
                        peek().position());
            }
            return new Binary(normalizeComparison(op.text()), left, right, op.position());
        }
        return left;
    }

    private static String normalizeComparison(String op) {
        return switch (op) {
            case "==" -> "=";
            case "<>" -> "!=";
            default -> op;
        };
    }

    private Node additive() {
        Node left = multiplicative();
        while (matchOperator("+", "-")) {
            Token op = next();
            left = new Binary(op.text(), left, multiplicative(), op.position());
        }
        return left;
    }

    private Node multiplicative() {
        Node left = unary();
        while (matchOperator("*", "/", "%")) {
            Token op = next();
            left = new Binary(op.text(), left, unary(), op.position());
        }
        return left;
    }

    private Node unary() {
        if (matchOperator("-")) {
            Token op = next();
            return new Unary("-", unary(), op.position());
        }
        return primary();
    }

    private Node primary() {
        Token t = peek();
        switch (t.kind()) {
            case NUMBER -> {
                next();
                return new Literal(new java.math.BigDecimal(t.text()), t.position());
            }
            case STRING -> {
                next();
                return new Literal(t.text(), t.position());
            }
            case LPAREN -> {
                next();
                Node inner = expression();
                if (peek().kind() != Kind.RPAREN) {
                    throw error("Expected ')' to close the group opened at position " + t.position() + ".",
                            peek().position());
                }
                next();
                return inner;
            }
            case IDENT -> {
                next();
                String name = t.text();
                if (peek().kind() == Kind.LPAREN) {
                    next();
                    List<Node> args = new ArrayList<>();
                    if (peek().kind() != Kind.RPAREN) {
                        args.add(expression());
                        while (peek().kind() == Kind.COMMA) {
                            next();
                            args.add(expression());
                        }
                    }
                    if (peek().kind() != Kind.RPAREN) {
                        throw error("Expected ')' to close the call to " + name.toUpperCase(Locale.ROOT) + "().",
                                peek().position());
                    }
                    next();
                    return new FunctionCall(name.toUpperCase(Locale.ROOT), args, t.position());
                }
                if ("TRUE".equalsIgnoreCase(name)) return new Literal(Boolean.TRUE, t.position());
                if ("FALSE".equalsIgnoreCase(name)) return new Literal(Boolean.FALSE, t.position());
                if ("NULL".equalsIgnoreCase(name)) return new Literal(null, t.position());
                if (peek().kind() == Kind.DOT) {
                    next();
                    Token field = peek();
                    if (field.kind() != Kind.IDENT) {
                        throw error("Expected a field name after '" + name + ".'.", field.position());
                    }
                    next();
                    String scope = name.toUpperCase(Locale.ROOT);
                    if (!scope.equals("NEW") && !scope.equals("OLD")) {
                        throw error("'" + name + "' is not a record scope. Use NEW.field or OLD.field.",
                                t.position());
                    }
                    return new Reference(scope, field.text(), t.position());
                }
                if (matchKeyword("AND") || matchKeyword("OR") || matchKeyword("NOT")) {
                    // e.g. "amount > 5 AND AND x" — the second AND lands here as a
                    // reference and the message would be misleading without this.
                    throw error("'" + name + "' is a keyword and cannot be used as a field name.", t.position());
                }
                return new Reference(null, name, t.position());
            }
            case OPERATOR -> throw error("Expected a value but found the operator '" + t.text() + "'.", t.position());
            case RPAREN -> throw error("Unmatched ')'.", t.position());
            case COMMA -> throw error("Unexpected ','.", t.position());
            case DOT -> throw error("Unexpected '.'.", t.position());
            case END -> throw error("The formula ends before the expression is complete.", t.position());
        }
        throw error("Unparseable expression.", t.position());
    }

    private ExpressionSyntaxException error(String message, int position) {
        return new ExpressionSyntaxException(message, source, position);
    }
}
