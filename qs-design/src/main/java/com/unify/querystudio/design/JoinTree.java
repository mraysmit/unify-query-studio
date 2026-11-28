package com.unify.querystudio.design;

import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the canvas's join graph into a SQL join tree (the FROM clause of the canonical query).
 *
 * <p>Access's rule decides what is ambiguous: an outer join may not point at a source that takes
 * part in an inner join, and two outer joins may not point at the same source. Otherwise the tree is
 * built from the sources no arrow points at, joined by their inner lines (several lines between two
 * sources form one compound condition), then the outer joins hang off it, outward. Sources with no
 * line are cross-joined. Ordering is by alias, so canvas order never changes the SQL.
 */
public final class JoinTree {

    private static final SqlParserPos POS = SqlParserPos.ZERO;

    private JoinTree() {
    }

    public static SqlNode from(List<Source> sources, List<JoinLine> lines) {
        Map<String, Source> byAlias = new HashMap<>();
        sources.forEach(s -> byAlias.put(s.alias(), s));

        Map<String, Set<String>> tailsOfHead = new HashMap<>();
        Set<String> inInner = new LinkedHashSet<>();
        for (JoinLine line : lines) {
            if (line.outer()) {
                tailsOfHead.computeIfAbsent(line.head(), k -> new TreeSet<>()).add(line.tail());
            } else {
                inInner.add(line.leftSource());
                inInner.add(line.rightSource());
            }
        }
        for (var entry : new java.util.TreeMap<>(tailsOfHead).entrySet()) {
            if (entry.getValue().size() > 1 || inInner.contains(entry.getKey())) {
                throw new AmbiguousJoinException(entry.getKey());
            }
        }

        List<String> core = sources.stream().map(Source::alias).filter(a -> !tailsOfHead.containsKey(a))
                .sorted(Comparator.naturalOrder()).toList();
        if (core.isEmpty()) {
            throw new AmbiguousJoinException(sources.getFirst().alias()); // a cycle of outer joins
        }

        Set<String> placed = new LinkedHashSet<>();
        SqlNode from = null;
        for (String start : core) {
            if (placed.contains(start)) {
                continue;
            }
            from = from == null ? item(byAlias.get(start)) : join(from, JoinType.CROSS, byAlias.get(start), null);
            placed.add(start);
            Deque<String> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                String current = queue.poll();
                TreeSet<String> neighbours = new TreeSet<>();
                for (JoinLine line : lines) {
                    if (!line.outer() && line.touches(current) && !placed.contains(line.other(current))) {
                        neighbours.add(line.other(current));
                    }
                }
                for (String next : neighbours) {
                    List<JoinLine> on = lines.stream().filter(l -> !l.outer() && l.touches(next)
                            && placed.contains(l.other(next))).toList();
                    from = join(from, JoinType.INNER, byAlias.get(next), condition(on));
                    placed.add(next);
                    queue.add(next);
                }
            }
        }

        boolean progress = true;
        while (progress) {
            progress = false;
            for (String head : new TreeSet<>(tailsOfHead.keySet())) {
                String tail = tailsOfHead.get(head).iterator().next();
                if (placed.contains(head) || !placed.contains(tail)) {
                    continue;
                }
                List<JoinLine> on = lines.stream().filter(l -> l.outer() && l.head().equals(head)).toList();
                from = join(from, JoinType.LEFT, byAlias.get(head), condition(on));
                placed.add(head);
                progress = true;
            }
        }
        if (placed.size() != sources.size()) {
            throw new AmbiguousJoinException(sources.stream().map(Source::alias).filter(a -> !placed.contains(a))
                    .sorted().findFirst().orElseThrow());
        }
        return from;
    }

    private static SqlNode item(Source source) {
        SqlIdentifier table = new SqlIdentifier(source.table(), POS);
        return source.aliased()
                ? SqlStdOperatorTable.AS.createCall(POS, table, new SqlIdentifier(source.alias(), POS))
                : table;
    }

    private static SqlNode join(SqlNode left, JoinType type, Source right, SqlNode condition) {
        return new SqlJoin(POS, left, SqlLiteral.createBoolean(false, POS), type.symbol(POS), item(right),
                (condition == null ? JoinConditionType.NONE : JoinConditionType.ON).symbol(POS), condition);
    }

    /** Each line as left.column = right.column, ANDed for compound keys. */
    private static SqlNode condition(List<JoinLine> lines) {
        List<SqlNode> equalities = new ArrayList<>();
        for (JoinLine l : lines) {
            equalities.add(SqlStdOperatorTable.EQUALS.createCall(POS,
                    new SqlIdentifier(List.of(l.leftSource(), l.leftColumn()), POS),
                    new SqlIdentifier(List.of(l.rightSource(), l.rightColumn()), POS)));
        }
        SqlNode result = equalities.getFirst();
        for (int i = 1; i < equalities.size(); i++) {
            result = SqlStdOperatorTable.AND.createCall(POS, result, equalities.get(i));
        }
        return result;
    }
}
