package com.unify.querystudio.design;

import com.unify.querystudio.compiler.CompilationException;
import com.unify.querystudio.compiler.CompiledQuery;
import com.unify.querystudio.compiler.QueryCompiler;
import com.unify.querystudio.compiler.ResultColumn;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Compiles a design, and when Calcite's validator rejects it, finds the design element at fault.
 * The validator reports a position in the canonical SQL, which the user never typed, so the failing
 * element is found by compiling smaller designs taken from the same design, in this order: each
 * source alone, the join lines added one by one, each grid column, then each criteria cell. The
 * first that fails is reported with the validator's message. If no single element fails, the error
 * belongs to the design as a whole and the {@link CompilationException} is thrown as it is.
 */
public final class DesignCompiler {

    /** A compiled design: the canonical SQL it was written as, and that SQL compiled for the target. */
    public record Result(String canonicalSql, CompiledQuery query) {
    }

    private DesignCompiler() {
    }

    /**
     * @throws DesignException      a design rule is broken, or the validator rejects one element
     * @throws CompilationException the validator rejects the design as a whole
     */
    public static Result compile(DesignView design, QueryCompiler compiler) {
        String sql = canonical(design);
        checkTypes(design, compiler);
        try {
            return new Result(sql, compiler.compile(sql));
        } catch (CompilationException e) {
            throw locate(design, compiler, e).orElse(e);
        }
    }

    /**
     * Access's type rules, which Calcite's implicit casts would otherwise let through: a join line
     * joins fields of comparable types, and Sum, Avg, StDev and Var take numbers. The types come from
     * Calcite's validator, by compiling one probe that selects every field involved. If the probe
     * itself fails, the elements are reported by {@link #locate} instead.
     */
    private static void checkTypes(DesignView design, QueryCompiler compiler) {
        List<GridColumn> probed = new ArrayList<>();
        for (JoinLine j : design.joins()) {
            probed.add(GridColumn.field(j.leftSource(), j.leftColumn()));
            probed.add(GridColumn.field(j.rightSource(), j.rightColumn()));
        }
        List<Integer> numeric = new ArrayList<>();
        for (int i = 0; i < design.columns().size(); i++) {
            GridColumn c = design.columns().get(i);
            if (design.totals() && c.total() instanceof Total.Aggregate a && a.numbersOnly() && !c.allFields()) {
                numeric.add(i);
                probed.add(new GridColumn(c.table(), c.field(), c.expression(), null, Total.GROUP_BY, null, true, List.of()));
            }
        }
        if (probed.isEmpty()) {
            return;
        }
        List<RelDataType> types;
        try {
            types = compiler.compile(DesignToSql.toSql(new DesignView(design.sources(), List.of(), probed, false, false, null)))
                    .columns().stream().map(ResultColumn::type).toList();
        } catch (CompilationException | DesignException e) {
            return;
        }
        for (int j = 0; j < design.joins().size(); j++) {
            RelDataType left = types.get(2 * j);
            RelDataType right = types.get(2 * j + 1);
            if (!comparable(left, right)) {
                JoinLine line = design.joins().get(j);
                throw DesignException.atJoin("Type mismatch in expression. You can't join " + line.leftSource() + "."
                        + line.leftColumn() + " (" + left.getSqlTypeName() + ") to " + line.rightSource() + "."
                        + line.rightColumn() + " (" + right.getSqlTypeName() + "); join fields of the same type.", j, null);
            }
        }
        for (int k = 0; k < numeric.size(); k++) {
            RelDataType type = types.get(2 * design.joins().size() + k);
            if (!SqlTypeUtil.isNumeric(type) && type.getSqlTypeName() != SqlTypeName.ANY) {
                int i = numeric.get(k);
                GridColumn c = design.columns().get(i);
                String what = c.field() != null ? c.field() : c.expression();
                throw new DesignException(((Total.Aggregate) c.total()).accessName() + " can only be used on number fields; "
                        + what + " is " + type.getSqlTypeName() + ".", i);
            }
        }
    }

    /**
     * The same type family (numbers with numbers, text with text). Calcite's own
     * {@code SqlTypeUtil.isComparable} also accepts text against numbers and dates, because it allows
     * implicit casts; Access does not join those. A field of unknown type (ANY) joins anything.
     */
    private static boolean comparable(RelDataType a, RelDataType b) {
        return a.getSqlTypeName() == SqlTypeName.ANY || b.getSqlTypeName() == SqlTypeName.ANY
                || a.getFamily() == b.getFamily();
    }

    private static String canonical(DesignView design) {
        try {
            return DesignToSql.toSql(design);
        } catch (AmbiguousJoinException e) {
            throw DesignException.atSource(e.getMessage(), e.table(), e);
        }
    }

    private static Optional<RuntimeException> locate(DesignView design, QueryCompiler compiler, CompilationException e) {
        List<Source> sources = design.sources();
        for (Source source : sources) {
            Optional<String> failure = failure(compiler, new DesignView(List.of(source), List.of(),
                    List.of(GridColumn.allFields(source.alias())), false, false, null));
            if (failure.isPresent()) {
                return Optional.of(DesignException.atSource(failure.get(), source.alias(), e));
            }
        }
        List<GridColumn> anyField = sources.isEmpty() ? List.of() : List.of(GridColumn.allFields(sources.getFirst().alias()));
        for (int j = 0; j < design.joins().size(); j++) {
            Optional<String> failure = failure(compiler, new DesignView(sources, design.joins().subList(0, j + 1),
                    anyField, false, false, null));
            if (failure.isPresent()) {
                return Optional.of(DesignException.atJoin(failure.get(), j, e));
            }
        }
        List<GridColumn> columns = design.columns();
        for (int i = 0; i < columns.size(); i++) {
            Optional<String> failure = failure(compiler, probe(design, i, null));
            if (failure.isPresent()) {
                return Optional.of(new DesignException(failure.get(), i, null, e));
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            List<String> cells = columns.get(i).criteria();
            for (int r = 0; r < cells.size(); r++) {
                if (cells.get(r).isBlank()) {
                    continue;
                }
                Optional<String> failure = failure(compiler, probe(design, i, r));
                if (failure.isPresent()) {
                    return Optional.of(new DesignException(failure.get(), i, r, e));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * One grid column, shown, with only criteria row {@code row} (none when null), on the design's
     * sources and joins. In a totals query the other Group By columns come too, hidden and without
     * criteria, since a field is valid there only beside the fields it is grouped with. A Where
     * column is probed as a plain field, which filters the same rows.
     */
    private static DesignView probe(DesignView design, int column, Integer row) {
        GridColumn c = design.columns().get(column);
        boolean where = c.total() instanceof Total.Where;
        boolean totals = design.totals() && !where;
        List<String> criteria = row == null ? List.of() : cellOnly(c.criteria(), row);
        GridColumn probed = new GridColumn(c.table(), c.field(), c.expression(), c.alias(),
                where ? Total.GROUP_BY : c.total(), null, true, criteria);
        List<GridColumn> columns = new ArrayList<>();
        columns.add(probed);
        if (totals) {
            for (int i = 0; i < design.columns().size(); i++) {
                GridColumn other = design.columns().get(i);
                if (i != column && other.total() instanceof Total.GroupBy) {
                    columns.add(new GridColumn(other.table(), other.field(), other.expression(), other.alias(),
                            other.total(), null, false, List.of()));
                }
            }
        }
        return new DesignView(design.sources(), design.joins(), columns, totals, false, null, false);
    }

    private static List<String> cellOnly(List<String> criteria, int row) {
        List<String> cells = new ArrayList<>(Collections.nCopies(criteria.size(), ""));
        cells.set(row, criteria.get(row));
        return cells;
    }

    /** The validator's message if the probe fails; a probe that breaks a design rule says nothing. */
    private static Optional<String> failure(QueryCompiler compiler, DesignView probe) {
        try {
            compiler.compile(DesignToSql.toSql(probe));
            return Optional.empty();
        } catch (CompilationException e) {
            return Optional.of(e.diagnostics().isEmpty() ? e.getMessage() : e.diagnostics().getFirst().message());
        } catch (DesignException | AmbiguousJoinException e) {
            return Optional.empty();
        }
    }
}
