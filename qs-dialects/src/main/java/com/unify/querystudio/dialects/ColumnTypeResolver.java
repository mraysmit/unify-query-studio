package com.unify.querystudio.dialects;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;

/**
 * Turns a column's JDBC metadata into the Calcite type the catalog exposes. Drivers report
 * types inconsistently, so each database supplies its own resolver.
 */
@FunctionalInterface
public interface ColumnTypeResolver {

    RelDataType resolve(JdbcColumn column, RelDataTypeFactory typeFactory);
}
