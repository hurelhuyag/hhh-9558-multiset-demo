package dev.hurlee.hibernate.multiset;

import java.util.List;

import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.sql.ast.tree.select.SortSpecification;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.type.BasicType;
import org.hibernate.type.internal.BasicTypeImpl;

/**
 * How a database spells MULTISET and how the value comes back:
 * <ul>
 *   <li>PostgreSQL: {@code array_agg(row(c1, c2, ...) order by ...)} — an anonymous {@code record[]},
 *       requested in binary wire format and decoded with the field types it carries;</li>
 *   <li>MySQL / MariaDB: {@code json_arrayagg(json_array(c1, c2, ...))} — a JSON array of positional
 *       arrays (no keys: smaller than {@code json_object}, and the ROW shape gives the positions).
 *       MariaDB supports {@code ORDER BY} inside the aggregate, MySQL does not;</li>
 *   <li>SQLite: {@code json_group_array(json_array(c1, c2, ...))}, {@code ORDER BY} inside the aggregate
 *       since 3.44 — the smallest database this works on.</li>
 * </ul>
 */
public record MultisetDialect(String aggregateFunction, String rowFunction, boolean orderByInsideAggregate, BasicType<Object[][]> rowsType) {

    public static final MultisetDialect POSTGRESQL = new MultisetDialect("array_agg", "row", true,
            new BasicTypeImpl<>(RecordArrayJavaType.INSTANCE, RecordArrayJdbcType.INSTANCE));
    public static final MultisetDialect MARIADB = new MultisetDialect("json_arrayagg", "json_array", true,
            new BasicTypeImpl<>(RecordArrayJavaType.INSTANCE, JsonRecordArrayJdbcType.INSTANCE));
    public static final MultisetDialect MYSQL = new MultisetDialect("json_arrayagg", "json_array", false,
            new BasicTypeImpl<>(RecordArrayJavaType.INSTANCE, JsonRecordArrayJdbcType.INSTANCE));
    public static final MultisetDialect SQLITE = new MultisetDialect("json_group_array", "json_array", true,
            new BasicTypeImpl<>(RecordArrayJavaType.INSTANCE, JsonRecordArrayJdbcType.INSTANCE));

    public static MultisetDialect of(Dialect dialect) {
        if (dialect instanceof PostgreSQLDialect) {
            return POSTGRESQL;
        }
        if (dialect instanceof MariaDBDialect) {
            return MARIADB;
        }
        if (dialect instanceof MySQLDialect) {
            return MYSQL;
        }
        if (dialect.getClass().getSimpleName().equals("SQLiteDialect")) { // hibernate-community-dialects, not a dependency
            return SQLITE;
        }
        throw new UnsupportedOperationException("@MultisetFetch is not implemented for " + dialect);
    }

    public Expression aggregate(List<? extends Expression> row, List<SortSpecification> orderBy) {
        return new MultisetAggregateExpression(aggregateFunction, rowFunction, row, orderByInsideAggregate ? orderBy : List.of(), rowsType);
    }

    /** Both dialects deliver a value that decodes to {@code Object[][]}; a nested multiset is an {@code Object[][]} field. */
    public boolean isRowsType(org.hibernate.metamodel.mapping.JdbcMapping mapping) {
        return mapping == rowsType;
    }
}
