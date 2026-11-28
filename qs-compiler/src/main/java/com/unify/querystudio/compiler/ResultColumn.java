package com.unify.querystudio.compiler;

import org.apache.calcite.rel.type.RelDataType;

/** A result column: its output name and the type the validator derived. */
public record ResultColumn(String name, RelDataType type) {
}
