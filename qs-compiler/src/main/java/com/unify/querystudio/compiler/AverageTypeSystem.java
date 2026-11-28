package com.unify.querystudio.compiler;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Calcite's type system, except that AVG, STDDEV and VAR of whole numbers are typed DOUBLE. The
 * databases return fractional results (SQLite a REAL, PostgreSQL a NUMERIC), but Calcite's default
 * keeps the integer type, so results were read back truncated (113 instead of 113.39). Calcite
 * derives all three through {@link #deriveAvgAggType}.
 */
final class AverageTypeSystem extends RelDataTypeSystemImpl {

    static final AverageTypeSystem INSTANCE = new AverageTypeSystem();

    private AverageTypeSystem() {
    }

    @Override
    public RelDataType deriveAvgAggType(RelDataTypeFactory typeFactory, RelDataType argumentType) {
        if (SqlTypeName.INT_TYPES.contains(argumentType.getSqlTypeName())) {
            return typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.DOUBLE),
                    argumentType.isNullable());
        }
        return super.deriveAvgAggType(typeFactory, argumentType);
    }
}
