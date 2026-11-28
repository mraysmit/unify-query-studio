package com.unify.querystudio.compiler;

import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.sql.util.SqlString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Named parameters are written {@code :name}, standard SQL's host-variable form. Calcite's parser
 * knows only positional {@code ?} markers, so names are turned into markers before parsing
 * ({@link #mark}) and back after Calcite writes SQL ({@link #unmark}), using the order of markers
 * Calcite reports in {@link SqlString#getDynamicParameters()}. Text inside quotes and comments is
 * never touched.
 *
 * <p>SQL assembled from several separately parsed pieces (the design grid's cells) gives each
 * parameter a stable index per name ({@link #index}), so its markers map back to the right names.
 */
public final class NamedParameters {

    /** SQL with {@code ?} markers, and the name of each marker in order. */
    public record Marked(String sql, List<String> names) {

        public Marked {
            names = List.copyOf(names);
        }

        /** The parsed node with each marker renumbered to its name's stable {@link #index}. */
        public SqlNode stable(SqlNode node) {
            return node.accept(new SqlShuttle() {
                @Override
                public SqlNode visit(SqlDynamicParam param) {
                    return new SqlDynamicParam(index(names.get(param.getIndex())), param.getParserPosition());
                }
            });
        }
    }

    private static final Map<String, Integer> INDEXES = new ConcurrentHashMap<>();
    private static final List<String> NAMES = new ArrayList<>();

    private NamedParameters() {
    }

    /** @throws ParameterException for a bare {@code ?}: parameters have names */
    public static Marked mark(String sql) {
        StringBuilder out = new StringBuilder();
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            int skipped = skipQuotedOrComment(sql, i);
            if (skipped > i) {
                out.append(sql, i, skipped);
                i = skipped;
                continue;
            }
            char c = sql.charAt(i);
            if (c == '?') {
                throw new ParameterException("Write parameters as :name, for example :MinTotal.");
            }
            if (c == ':' && i + 1 < sql.length() && isNameStart(sql.charAt(i + 1))
                    && (i == 0 || sql.charAt(i - 1) != ':')) {
                int end = i + 1;
                while (end < sql.length() && isNamePart(sql.charAt(end))) end++;
                names.add(sql.substring(i + 1, end));
                out.append('?');
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return new Marked(out.toString(), names);
    }

    /** Calcite's SQL with each marker written back as {@code :name}. */
    public static String unmark(SqlString sql, IntFunction<String> nameOfIndex) {
        List<Integer> order = sql.getDynamicParameters() == null ? List.of() : sql.getDynamicParameters();
        return unmark(sql.getSql(), order, nameOfIndex);
    }

    static String unmark(String text, List<Integer> order, IntFunction<String> nameOfIndex) {
        StringBuilder out = new StringBuilder();
        int marker = 0;
        int i = 0;
        while (i < text.length()) {
            int skipped = skipQuotedOrComment(text, i);
            if (skipped > i) {
                out.append(text, i, skipped);
                i = skipped;
                continue;
            }
            char c = text.charAt(i);
            if (c == '?' && marker < order.size()) {
                out.append(':').append(nameOfIndex.apply(order.get(marker++)));
            } else {
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    /** A stable index for a parameter name, the same wherever the name is used. */
    public static int index(String name) {
        synchronized (NAMES) {
            return INDEXES.computeIfAbsent(name, n -> {
                NAMES.add(n);
                return NAMES.size() - 1;
            });
        }
    }

    /** The name with this stable index. */
    public static String name(int index) {
        synchronized (NAMES) {
            return NAMES.get(index);
        }
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The index after a quoted string, quoted identifier or comment starting at {@code i}; {@code i} if none. */
    private static int skipQuotedOrComment(String s, int i) {
        char c = s.charAt(i);
        if (c == '\'' || c == '"') {
            int j = i + 1;
            while (j < s.length()) {
                if (s.charAt(j) == c) {
                    if (j + 1 < s.length() && s.charAt(j + 1) == c) {
                        j += 2;
                        continue;
                    }
                    return j + 1;
                }
                j++;
            }
            return s.length();
        }
        if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
            int end = s.indexOf('\n', i);
            return end < 0 ? s.length() : end;
        }
        if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
            int end = s.indexOf("*/", i + 2);
            return end < 0 ? s.length() : end + 2;
        }
        return i;
    }
}
