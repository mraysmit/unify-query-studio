package com.unify.querystudio.dialects;

import org.apache.calcite.config.NullCollation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialectSupportTest {

    @Test
    void sqliteBundlesItsDialectTypesAndBinder() {
        DialectSupport sqlite = DialectSupport.forId("sqlite");
        assertThat(sqlite.id()).isEqualTo("sqlite");
        assertThat(sqlite.displayName()).isEqualTo("SQLite");
        assertThat(sqlite.sqlDialect()).isSameAs(QueryStudioSqliteDialect.INSTANCE);
        assertThat(sqlite.columnTypes()).isInstanceOf(SqliteColumnTypeResolver.class);
        assertThat(sqlite.binder()).isInstanceOf(SqliteParameterBinder.class);
        assertThat(sqlite.nullCollation()).isEqualTo(NullCollation.LOW);
    }

    @Test
    void sqliteHidesItsSystemTables() {
        DialectSupport sqlite = DialectSupport.forId("sqlite");
        assertThat(sqlite.isSystemTable("sqlite_sequence")).isTrue();
        assertThat(sqlite.isSystemTable("sqlite_stat1")).isTrue();
        assertThat(sqlite.isSystemTable("Orders")).isFalse();
    }

    @Test
    void unknownDialectsAreRefused() {
        assertThatThrownBy(() -> DialectSupport.forId("oracle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oracle");
    }
}
