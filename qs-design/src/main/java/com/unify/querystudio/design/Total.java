package com.unify.querystudio.design;

import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlKind;

import java.util.Map;

/**
 * The design grid's Total row, as in Access. Group By, Where and Expression are design concepts
 * with no single SQL counterpart; an aggregate is a Calcite {@link SqlAggFunction}.
 */
public sealed interface Total {

    Total GROUP_BY = new GroupBy();
    Total WHERE = new Where();
    Total EXPRESSION = new Expression();

    static Total aggregate(SqlAggFunction function) {
        return new Aggregate(function);
    }

    /** Group by this column; criteria on it go to HAVING, as in Access. */
    record GroupBy() implements Total {
    }

    /** Filter rows before grouping; never shown; criteria go to WHERE. */
    record Where() implements Total {
    }

    /** The Field cell already contains aggregates; used as written, not grouped. */
    record Expression() implements Total {
    }

    /** An aggregate of the column; its default output name is Access's {@code SumOfField} form. */
    record Aggregate(SqlAggFunction function) implements Total {

        private static final Map<SqlKind, String> ACCESS_NAMES = Map.of(
                SqlKind.SUM, "Sum", SqlKind.AVG, "Avg", SqlKind.MIN, "Min", SqlKind.MAX, "Max",
                SqlKind.COUNT, "Count", SqlKind.STDDEV_SAMP, "StDev", SqlKind.VAR_SAMP, "Var");

        public Aggregate {
            if (!ACCESS_NAMES.containsKey(function.getKind())) {
                throw new IllegalArgumentException("Not an Access Total option: " + function.getName());
            }
        }

        /** Access's name for the option: Sum, Avg, Min, Max, Count, StDev, Var. */
        public String accessName() {
            return ACCESS_NAMES.get(function.getKind());
        }

        /** Sum, Avg, StDev and Var take numbers only; Min, Max and Count take any field. */
        public boolean numbersOnly() {
            return switch (function.getKind()) {
                case SUM, AVG, STDDEV_SAMP, VAR_SAMP -> true;
                default -> false;
            };
        }
    }
}
