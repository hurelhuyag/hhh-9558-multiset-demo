package dev.hurlee.hibernate.multiset;

import java.util.List;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.JdbcMappingContainer;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.SelfRenderingExpression;
import org.hibernate.sql.ast.tree.select.SortSpecification;

/** Renders {@code <aggregate>(<row>(c1, c2, ...) [order by o1, o2])}, e.g. {@code array_agg(row(...))} or {@code json_arrayagg(json_array(...))}. */
public class MultisetAggregateExpression implements SelfRenderingExpression {

    private final String aggregateFunction;
    private final String rowFunction;
    private final List<? extends Expression> rowColumns;
    private final List<SortSpecification> orderBy;
    private final JdbcMappingContainer type;

    public MultisetAggregateExpression(String aggregateFunction, String rowFunction, List<? extends Expression> rowColumns,
                                       List<SortSpecification> orderBy, JdbcMappingContainer type) {
        this.aggregateFunction = aggregateFunction;
        this.rowFunction = rowFunction;
        this.rowColumns = rowColumns;
        this.orderBy = orderBy;
        this.type = type;
    }

    @Override
    public void renderToSql(SqlAppender appender, SqlAstTranslator<?> walker, SessionFactoryImplementor sessionFactory) {
        appender.appendSql(aggregateFunction);
        appender.appendSql('(');
        appender.appendSql(rowFunction);
        appender.appendSql('(');
        renderList(rowColumns, appender, walker);
        appender.appendSql(')');
        if (!orderBy.isEmpty()) {
            appender.appendSql(" order by ");
            renderList(orderBy, appender, walker); // the translator renders direction and (emulated) null precedence
        }
        appender.appendSql(')');
    }

    private static void renderList(List<? extends SqlAstNode> columns, SqlAppender appender, SqlAstTranslator<?> walker) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                appender.appendSql(',');
            }
            walker.render(columns.get(i), SqlAstNodeRenderingMode.DEFAULT);
        }
    }

    @Override
    public JdbcMappingContainer getExpressionType() {
        return type;
    }
}
