package com.unify.querystudio.design;

import com.unify.querystudio.compiler.NamedParameters;
import com.unify.querystudio.compiler.ParameterException;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlShuttle;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Grid cells hold standard SQL, parsed by Calcite with the canonical configuration
 * ({@code "identifier"}, {@code 'text'}, {@code DATE '2026-11-01'}). The one designer convention is
 * the implied left operand in a criteria cell: a segment that starts with an operator ({@code >= 100},
 * {@code IN (…)}, {@code LIKE '…'}, {@code IS NULL}) applies to the cell's column, and a bare value
 * means equality. Each top-level AND/OR segment gets the operand; BETWEEN's own AND is kept.
 */
public final class CellSql {
    // Named parameters (:name) may appear wherever a value may; see NamedParameters.

    private static final SqlParser.Config CANONICAL = SqlParser.config()
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(true);

    private static final String OPERAND = "$operand$";
    private static final Set<String> COMPARISONS = Set.of(">=", "<=", "<>", "!=", "=", ">", "<");
    private static final Set<String> OPERATOR_WORDS = Set.of("LIKE", "IN", "BETWEEN", "IS", "NOT");
    private static final Set<SqlKind> PREDICATES = EnumSet.of(SqlKind.LIKE, SqlKind.IN, SqlKind.NOT_IN, SqlKind.BETWEEN,
            SqlKind.IS_NULL, SqlKind.IS_NOT_NULL, SqlKind.EXISTS, SqlKind.NOT, SqlKind.AND, SqlKind.OR);

    private CellSql() {
    }

    /** A Field cell's expression. */
    public static SqlNode expression(String text) {
        if (text == null || text.isBlank()) {
            throw new CriteriaException("The Field cell is empty.");
        }
        return parse(text);
    }

    /**
     * @param column the expression the implied operand stands for: the column, or its aggregate when
     *               the criterion applies to a total
     * @return the predicate, or {@code null} when the cell is empty
     */
    public static SqlNode predicate(String cell, SqlNode column) {
        if (cell == null || cell.isBlank()) {
            return null;
        }
        List<Token> tokens = tokenize(cell.strip());
        StringBuilder sql = new StringBuilder();
        List<Token> segment = new ArrayList<>();
        int depth = 0;
        boolean betweenPending = false;
        for (Token t : tokens) {
            if (t.text().equals("(")) depth++;
            if (t.text().equals(")")) depth--;
            if (depth == 0 && t.isWord("BETWEEN")) betweenPending = true;
            if (depth == 0 && (t.isWord("AND") || t.isWord("OR"))) {
                if (betweenPending && t.isWord("AND")) {
                    betweenPending = false;
                } else {
                    sql.append(segment(segment)).append(' ').append(t.text().toUpperCase(Locale.ROOT)).append(' ');
                    segment = new ArrayList<>();
                    continue;
                }
            }
            segment.add(t);
        }
        sql.append(segment(segment));
        return substitute(parse(sql.toString()), column);
    }

    /** SQL as cell text: Calcite's own SQL, on one line. */
    public static String text(SqlNode node) {
        return NamedParameters.unmark(node.toSqlString(c -> c.withDialect(CalciteSqlDialect.DEFAULT)), NamedParameters::name)
                .replaceAll("\\s*\\R\\s*", " ").strip();
    }

    /** One segment, with the column's placeholder where the cell leaves it out. */
    private static String segment(List<Token> tokens) {
        List<Token> body = tokens.stream().filter(t -> !t.isSpace()).toList();
        if (body.isEmpty()) {
            throw new CriteriaException("Something is missing around AND or OR.");
        }
        String text = join(tokens);
        String operand = "\"" + OPERAND + "\"";
        Token first = body.getFirst();
        if (!first.quoted() && (COMPARISONS.contains(first.text()) || first.isWordIn(OPERATOR_WORDS))) {
            return operand + " " + text;
        }
        return isPredicate(text) ? text : operand + " = " + text;
    }

    private static boolean isPredicate(String sql) {
        try {
            SqlKind kind = SqlParser.create(NamedParameters.mark(sql).sql(), CANONICAL).parseExpression().getKind();
            return kind.belongsTo(SqlKind.COMPARISON) || PREDICATES.contains(kind);
        } catch (SqlParseException | ParameterException e) {
            return false;
        }
    }

    private static SqlNode parse(String sql) {
        try {
            NamedParameters.Marked marked = NamedParameters.mark(sql);
            return marked.stable(SqlParser.create(marked.sql(), CANONICAL).parseExpression());
        } catch (ParameterException e) {
            throw new CriteriaException(e.getMessage(), e);
        } catch (SqlParseException e) {
            throw new CriteriaException(e.getMessage().lines().findFirst().orElse("The cell cannot be read."), e);
        }
    }

    private static SqlNode substitute(SqlNode node, SqlNode column) {
        return node.accept(new SqlShuttle() {
            @Override
            public SqlNode visit(SqlIdentifier id) {
                return id.isSimple() && id.getSimple().equals(OPERAND) ? column.clone(SqlParserPos.ZERO) : id;
            }
        });
    }

    // ------------------------------------------------------------------ tokens

    /** Just enough lexing to find top-level AND/OR and the first word: quotes and brackets are respected. */
    private record Token(String text, boolean quoted) {
        boolean isSpace() {
            return !quoted && text.isBlank();
        }

        boolean isWord(String word) {
            return !quoted && text.equalsIgnoreCase(word);
        }

        boolean isWordIn(Set<String> words) {
            return !quoted && words.contains(text.toUpperCase(Locale.ROOT));
        }
    }

    private static String join(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        tokens.forEach(t -> out.append(t.text()));
        return out.toString().strip();
    }

    private static List<Token> tokenize(String s) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int start = i;
            if (Character.isWhitespace(c)) {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                tokens.add(new Token(s.substring(start, i), false));
            } else if (c == '\'' || c == '"') {
                i = closingQuote(s, i, c);
                tokens.add(new Token(s.substring(start, i), true));
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || "_$.".indexOf(s.charAt(i)) >= 0)) i++;
                tokens.add(new Token(s.substring(start, i), false));
            } else if (s.startsWith(">=", i) || s.startsWith("<=", i) || s.startsWith("<>", i) || s.startsWith("!=", i)
                    || s.startsWith("||", i)) {
                i += 2;
                tokens.add(new Token(s.substring(start, i), false));
            } else {
                i++;
                tokens.add(new Token(s.substring(start, i), false));
            }
        }
        return tokens;
    }

    /** The index after the closing quote; the quote doubled inside stands for itself. */
    private static int closingQuote(String s, int open, char quote) {
        int i = open + 1;
        while (i < s.length()) {
            if (s.charAt(i) == quote) {
                if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new CriteriaException(quote == '\'' ? "Close the quote around the text." : "Close the quote around the name.");
    }
}
