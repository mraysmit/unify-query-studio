package com.unify.querystudio.compiler;

import com.unify.querystudio.dialects.DialectSupport;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.rules.AggregateReduceFunctionsRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.sql2rel.RelFieldTrimmer;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlString;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimestampString;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Properties;

/**
 * Compiles canonical Calcite SQL into SQL for one target database, using Calcite end to end:
 * parse, validate against the connection's schema, convert to relational algebra, then write
 * target SQL with the dialect's {@code SqlDialect}. The result is the complete SQL, values written in,
 * and the same SQL with criteria values as bind parameters for preview runs. Aggregates the target
 * lacks are rewritten; otherwise the SQL means what the database says it means.
 *
 * <p>Instances are immutable and thread-safe; each compile uses its own {@link Planner}.
 */
public final class QueryCompiler {

    /** Canonical SQL: Calcite syntax, double-quoted identifiers kept exactly as the database names them. */
    public static final SqlParser.Config CANONICAL = SqlParser.config()
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(true);

    private final FrameworkConfig config;
    private final DialectSupport dialect;

    public QueryCompiler(SchemaPlus schema, DialectSupport dialect) {
        this.dialect = dialect;
        Properties connection = new Properties();
        // The Planner takes NULL ordering from here, not from the validator config (phase 1 finding).
        connection.setProperty(CalciteConnectionProperty.DEFAULT_NULL_COLLATION.camelName(), dialect.nullCollation().name());
        this.config = Frameworks.newConfigBuilder()
                .parserConfig(CANONICAL)
                .defaultSchema(schema)
                .typeSystem(AverageTypeSystem.INSTANCE)
                // Otherwise IN lists of 20+ values become a join against VALUES.
                .sqlToRelConverterConfig(SqlToRelConverter.config().withInSubQueryThreshold(Integer.MAX_VALUE))
                .context(Contexts.of(new CalciteConnectionConfigImpl(connection)))
                .build();
    }

    public CompiledQuery compile(String canonicalSql) {
        Planner planner = Frameworks.getPlanner(config);
        try {
            NamedParameters.Marked marked = NamedParameters.mark(canonicalSql);
            List<String> names = marked.names();
            SqlNode parsed = planner.parse(marked.sql());
            if (!parsed.isA(SqlKind.QUERY)) {
                throw new CompilationException(List.of(Diagnostic.whole("Only SELECT queries can be designed and run.")), null);
            }
            RelRoot root = planner.rel(planner.validate(parsed));
            RelNode complete = reduceUnsupportedAggregates(root.project());
            List<QueryParameter> prompts = prompts(complete, names);
            Parameters parameters = new Parameters(complete.getCluster().getRexBuilder(), names.size());
            SqlString sql = write(conditions(complete, parameters));
            List<BoundValue> ordered = new ArrayList<>();
            if (sql.getDynamicParameters() != null) {
                for (int index : sql.getDynamicParameters()) {
                    if (index < names.size()) {
                        String name = names.get(index);
                        QueryParameter prompt = prompts.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
                        ordered.add(new BoundValue(null, prompt.type(), name));
                    } else {
                        ordered.add(parameters.values.get(index - names.size()));
                    }
                }
            }
            List<ResultColumn> columns = new ArrayList<>();
            for (RelDataTypeField field : root.validatedRowType.getFieldList()) {
                columns.add(new ResultColumn(field.getName(), field.getType()));
            }
            String completeSql = NamedParameters.unmark(write(complete), names::get);
            return new CompiledQuery(normalise(completeSql), normalise(sql.getSql()), ordered, columns,
                    references(root.rel), prompts);
        } catch (SqlParseException e) {
            SqlParserPos p = e.getPos();
            throw new CompilationException(List.of(new Diagnostic(p.getLineNum(), p.getColumnNum(),
                    p.getEndLineNum(), p.getEndColumnNum(), firstLine(e.getMessage()))), e);
        } catch (ValidationException | RelConversionException | RuntimeException e) {
            // A saved query used as a source fails when Calcite reads its row type or expands it.
            SavedQuerySchema.InvalidSavedQueryException invalid = SavedQuerySchema.find(e);
            if (invalid != null) {
                throw new CompilationException(List.of(Diagnostic.whole(invalid.getMessage())), e);
            }
            switch (e) {
                case ParameterException p -> throw new CompilationException(List.of(Diagnostic.whole(p.getMessage())), p);
                case ValidationException v -> throw new CompilationException(List.of(diagnostic(v)), v);
                case RelConversionException r ->
                        throw new CompilationException(List.of(Diagnostic.whole(firstLine(r.getMessage()))), r);
                case RuntimeException r -> throw r;
                default -> throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The named parameters, typed by the validator from where each is used, once each in the order
     * they first appear. A name used with two kinds of type is refused: one value cannot be both.
     */
    private static List<QueryParameter> prompts(RelNode rel, List<String> names) {
        Map<Integer, RelDataType> types = new HashMap<>();
        rel.accept(new RelHomogeneousShuttle() {
            @Override
            public RelNode visit(RelNode other) {
                other.accept(new RexShuttle() {
                    @Override
                    public RexNode visitDynamicParam(RexDynamicParam param) {
                        types.putIfAbsent(param.getIndex(), param.getType());
                        return param;
                    }
                });
                return super.visit(other);
            }
        });
        Map<String, RelDataType> byName = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            RelDataType type = types.get(i);
            if (type == null) {
                continue;
            }
            RelDataType seen = byName.putIfAbsent(names.get(i), type);
            if (seen != null && seen.getFamily() != type.getFamily()) {
                throw new CompilationException(List.of(Diagnostic.whole("The parameter :" + names.get(i)
                        + " is used as both " + seen.getSqlTypeName() + " and " + type.getSqlTypeName()
                        + "; use a parameter for each.")), null);
            }
        }
        List<QueryParameter> prompts = new ArrayList<>();
        byName.forEach((name, type) -> prompts.add(new QueryParameter(name, type)));
        return prompts;
    }

    /**
     * The fields of each table the query reads. Calcite's {@link RelFieldTrimmer} puts a projection of
     * just the fields used (in the select list, joins, criteria and sorts) above each table scan; a
     * scan left without one uses every field. Saved queries are views, so their tables count too.
     */
    private static List<FieldReference> references(RelNode rel) {
        RelNode trimmed = new RelFieldTrimmer(null, RelFactories.LOGICAL_BUILDER.create(rel.getCluster(), null)).trim(rel);
        Set<FieldReference> refs = new LinkedHashSet<>();
        new RelVisitor() {
            @Override
            public void visit(RelNode node, int ordinal, RelNode parent) {
                if (node instanceof TableScan scan) {
                    List<RelDataTypeField> fields = scan.getRowType().getFieldList();
                    ImmutableBitSet used = parent instanceof Project project
                            ? RelOptUtil.InputFinder.bits(project.getProjects(), null)
                            : ImmutableBitSet.range(fields.size());
                    for (int i : used) {
                        RelDataTypeField f = fields.get(i);
                        refs.add(new FieldReference(scan.getTable().getQualifiedName(), f.getName(),
                                f.getType().getSqlTypeName().getName()));
                    }
                }
                super.visit(node, ordinal, parent);
            }
        }.go(trimmed);
        return List.copyOf(refs);
    }

    /** Target SQL, one clause per line; the dialect writes and escapes every literal. */
    private SqlString write(RelNode rel) {
        return new RelToSqlConverter(dialect.sqlDialect()).visitRoot(rel).asStatement()
                .toSqlString(c -> c.withDialect(dialect.sqlDialect()).withClauseStartsLine(true).withIndentation(0));
    }

    /**
     * Aggregates the target cannot run (SQLite has no STDDEV_SAMP or VAR_SAMP) are rewritten by
     * Calcite's {@link AggregateReduceFunctionsRule} into SUM and COUNT arithmetic. Only the kinds the
     * dialect declares unsupported are reduced, so other databases keep their own functions.
     */
    private RelNode reduceUnsupportedAggregates(RelNode rel) {
        EnumSet<SqlKind> unsupported = EnumSet.noneOf(SqlKind.class);
        rel.accept(new RelHomogeneousShuttle() {
            @Override
            public RelNode visit(RelNode other) {
                if (other instanceof Aggregate aggregate) {
                    for (AggregateCall call : aggregate.getAggCallList()) {
                        SqlKind kind = call.getAggregation().getKind();
                        if (!dialect.sqlDialect().supportsAggregateFunction(kind)) {
                            unsupported.add(kind);
                        }
                    }
                }
                return super.visit(other);
            }
        });
        if (unsupported.isEmpty()) {
            return rel;
        }
        HepPlanner planner = new HepPlanner(new HepProgramBuilder()
                .addRuleInstance(AggregateReduceFunctionsRule.Config.DEFAULT.withFunctionsToReduce(unsupported).toRule())
                .build());
        planner.setRoot(rel);
        return planner.findBestExp();
    }

    /**
     * Applies a shuttle to the conditions of filters (WHERE, HAVING) and joins only. A condition that
     * is a bare literal (a cross join's ON TRUE) is not a criterion and is left alone.
     */
    private static RelNode conditions(RelNode rel, RexShuttle shuttle) {
        return rel.accept(new RelHomogeneousShuttle() {
            @Override
            public RelNode visit(RelNode other) {
                RelNode visited = super.visit(other);
                RexNode condition = switch (visited) {
                    case Filter f -> f.getCondition();
                    case Join j -> j.getCondition();
                    default -> null;
                };
                return condition == null || condition instanceof RexLiteral ? visited : visited.accept(shuttle);
            }
        });
    }

    /** Replaces literal values in conditions with bind parameters, remembering each value and type. */
    private static final class Parameters extends RexShuttle {
        private final RexBuilder rex;
        private final int named;
        private final List<BoundValue> values = new ArrayList<>();

        /** @param named the markers the query's named parameters already use (0 to named - 1) */
        Parameters(RexBuilder rex, int named) {
            this.rex = rex;
            this.named = named;
        }

        @Override
        public RexNode visitCall(RexCall call) {
            if (call.getKind() == SqlKind.SEARCH) {
                return RexUtil.expandSearch(rex, null, call).accept(this);
            }
            return super.visitCall(call);
        }

        @Override
        public RexNode visitLiteral(RexLiteral literal) {
            SqlTypeName type = literal.getTypeName();
            if (literal.isNull() || type == SqlTypeName.SYMBOL || type == SqlTypeName.SARG) {
                return literal;
            }
            values.add(new BoundValue(value(literal), literal.getType()));
            return rex.makeDynamicParam(literal.getType(), named + values.size() - 1);
        }

        private static Object value(RexLiteral literal) {
            return switch (literal.getTypeName().getFamily()) {
                case CHARACTER -> literal.getValueAs(String.class);
                case NUMERIC -> literal.getValueAs(BigDecimal.class);
                case BOOLEAN -> literal.getValueAs(Boolean.class);
                case DATE -> literal.getValueAs(DateString.class);
                case TIME -> literal.getValueAs(TimeString.class);
                case TIMESTAMP -> literal.getValueAs(TimestampString.class);
                case null, default -> literal.getValue2();
            };
        }
    }

    /** The validator's message and position, without Calcite's "From line …" prefix. */
    private static Diagnostic diagnostic(ValidationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof CalciteContextException c) {
            String message = c.getCause() != null ? c.getCause().getMessage() : c.getMessage();
            return new Diagnostic(c.getPosLine(), c.getPosColumn(), c.getEndPosLine(), c.getEndPosColumn(), message);
        }
        return Diagnostic.whole(firstLine(e.getMessage()));
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("").strip();
    }

    /** Calcite writes the platform line separator; output is identical on every OS. */
    private static String normalise(String sql) {
        return sql.replace("\r\n", "\n").strip();
    }
}
