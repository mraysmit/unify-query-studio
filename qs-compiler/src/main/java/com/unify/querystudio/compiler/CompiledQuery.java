package com.unify.querystudio.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A query compiled for one target database.
 *
 * @param sql             the designer's output: complete SQL in the target dialect, every value written in
 *                        as a literal and named parameters kept as {@code :name}, ready to hand to any
 *                        system that runs it
 * @param targetSql       the same SQL with {@code ?} for every criteria value and parameter, for Query
 *                        Studio's own preview runs
 * @param parameters      the values for those markers, in order, each with its Calcite type; a named
 *                        parameter's value is supplied by {@link #bind}
 * @param columns         result columns, from the validated row type
 * @param references      the fields of database tables the query uses, with their types (its catalog fingerprint)
 * @param queryParameters the named parameters the query asks for, each once, in the order they first appear
 */
public record CompiledQuery(String sql, String targetSql, List<BoundValue> parameters, List<ResultColumn> columns,
                            List<FieldReference> references, List<QueryParameter> queryParameters) {

    public CompiledQuery {
        parameters = List.copyOf(parameters);
        columns = List.copyOf(columns);
        references = List.copyOf(references);
        queryParameters = List.copyOf(queryParameters);
    }

    public CompiledQuery(String sql, String targetSql, List<BoundValue> parameters, List<ResultColumn> columns,
                         List<FieldReference> references) {
        this(sql, targetSql, parameters, columns, references, List.of());
    }

    /**
     * The query with a value for every named parameter, ready to run. A value may be null.
     *
     * @throws ParameterException if a parameter has no value, or a value of the wrong type
     */
    public CompiledQuery bind(Map<String, ?> values) {
        List<BoundValue> bound = new ArrayList<>();
        for (BoundValue p : parameters) {
            if (p.parameter() == null) {
                bound.add(p);
                continue;
            }
            if (!values.containsKey(p.parameter())) {
                throw new ParameterException("Enter a value for :" + p.parameter() + ".");
            }
            QueryParameter prompt = queryParameters.stream().filter(q -> q.name().equals(p.parameter())).findFirst()
                    .orElseThrow();
            bound.add(new BoundValue(prompt.convert(values.get(p.parameter())), p.type(), p.parameter()));
        }
        return new CompiledQuery(sql, targetSql, bound, columns, references, queryParameters);
    }
}
