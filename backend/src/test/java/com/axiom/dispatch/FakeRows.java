package com.axiom.dispatch;

import org.mockito.Mockito;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * A {@link ResultSet} backed by a map of column name to value.
 *
 * <p>Needed because the services under test map rows with real
 * {@code RowMapper}s: stubbing {@code JdbcTemplate.query} to return a canned
 * list would skip the mapping entirely, and the mapping is where a column-name
 * typo actually lives.
 */
final class FakeRows {

    private FakeRows() {}

    static ResultSet row(Map<String, Object> values) {
        return Mockito.mock(ResultSet.class, Mockito.withSettings().defaultAnswer(invocation -> {
            Object first = invocation.getArguments().length > 0 ? invocation.getArgument(0) : null;
            Object value = first instanceof String name ? values.get(name) : null;
            return switch (invocation.getMethod().getName()) {
                case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                case "getBoolean" -> value != null && (Boolean) value;
                case "getString" -> value == null ? null : String.valueOf(value);
                case "getTimestamp" -> toTimestamp(value);
                case "wasNull" -> value == null;
                default -> value;
            };
        }));
    }

    private static Timestamp toTimestamp(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp;
        if (value instanceof Instant instant) return Timestamp.from(instant);
        throw new IllegalArgumentException("Not a timestamp: " + value);
    }
}
