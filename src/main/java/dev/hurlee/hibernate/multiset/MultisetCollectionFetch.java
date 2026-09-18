package dev.hurlee.hibernate.multiset;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.hibernate.engine.FetchTiming;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.ast.SqlAstJoinType;
import org.hibernate.sql.ast.spi.FromClauseAccess;
import org.hibernate.sql.ast.spi.SqlAstCreationState;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.from.PluralTableGroup;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.results.graph.AssemblerCreationState;
import org.hibernate.sql.results.graph.DomainResult;
import org.hibernate.sql.results.graph.DomainResultCreationState;
import org.hibernate.sql.results.graph.Fetch;
import org.hibernate.sql.results.graph.FetchParent;
import org.hibernate.sql.results.graph.Fetchable;
import org.hibernate.sql.results.graph.InitializerParent;
import org.hibernate.sql.results.graph.collection.CollectionInitializer;
import org.hibernate.sql.results.graph.collection.internal.CollectionFetch;
import org.hibernate.sql.results.graph.internal.ImmutableFetchList;
import org.hibernate.sql.ast.spi.SqlSelection;
import org.hibernate.sql.results.internal.SqlSelectionImpl;
import org.hibernate.type.descriptor.java.JavaType;

/**
 * Fetch that adds ONE selection to the owner's select list: a correlated
 * {@code (select array_agg(row(<element columns>) order by <pk>) from <element table> where <fk> = <owner pk>)}
 * subquery. The element entity fetch is created as a <em>nested</em> fetch, so its column
 * selections resolve to positions inside the decoded {@code record} instead of the outer select.
 */
public class MultisetCollectionFetch extends CollectionFetch {

    private final MultisetDialect dialect;
    private final DomainResult<?> collectionKeyResult;
    private final SqlSelection aggregateSelection;
    private final JdbcMapping[] rowJdbcMappings;
    /** declared decimal scale per ROW position (JSON numbers lose it), or null */
    private final Integer[] rowScales;
    private final ImmutableFetchList fetches;
    private final Fetch elementFetch;
    private final Fetch indexFetch;
    /** ROW entries after the element's own columns, in position order: {@link RowSegment} or {@link NestedExpression}. */
    private final List<Object> extras = new ArrayList<>();
    private int rowSize;

    private record NestedExpression(Expression expression, JdbcMapping jdbcMapping) {
    }

    /**
     * @param existingJoin a join to the collection that was already created (by an entity graph or
     *                     fetch profile) and detached from the owner, or {@code null} to create one
     */
    public MultisetCollectionFetch(
            NavigablePath fetchedPath,
            MultisetPluralAttributeMapping attribute,
            FetchParent fetchParent,
            TableGroupJoin existingJoin,
            DomainResultCreationState creationState) {
        super(fetchedPath, attribute, fetchParent);

        SqlAstCreationState sqlAstCreationState = creationState.getSqlAstCreationState();
        FromClauseAccess fromClauseAccess = sqlAstCreationState.getFromClauseAccess();
        dialect = MultisetDialect.of(sqlAstCreationState.getCreationContext().getSessionFactory().getJdbcServices().getDialect());
        TableGroup ownerTableGroup = fromClauseAccess.getTableGroup(fetchParent.getNavigablePath());

        // only needed when the collection key is not the owner's identifier (property-ref)
        collectionKeyResult = attribute.getKeyDescriptor().getTargetPart() == attribute.findContainingEntityMapping().getIdentifierMapping()
                ? null
                : attribute.getKeyDescriptor().createTargetDomainResult(fetchedPath, ownerTableGroup, fetchParent, creationState);

        // A table group for the collection that is deliberately NOT joined to the owner:
        // it only lives inside the correlated subquery. The join predicate is reused as the subquery's WHERE.
        TableGroupJoin join = existingJoin;
        if (join == null) {
            join = attribute.createTableGroupJoin(fetchedPath, ownerTableGroup, null, null,
                    SqlAstJoinType.INNER, true, false, sqlAstCreationState);
            fromClauseAccess.registerTableGroup(fetchedPath, join.getJoinedGroup());
        }
        // one-to-many: the element entity's table; @ElementCollection / many-to-many: the collection table (+ joined element)
        PluralTableGroup collectionTableGroup = (PluralTableGroup) join.getJoinedGroup();

        List<ColumnReference> orderBy = new ArrayList<>();
        if (attribute.getIndexDescriptor() != null) {
            attribute.getIndexDescriptor().forEachSelectable((i, c) -> orderBy.add(columnReference(collectionTableGroup, c)));
        } else if (attribute.elementEntity() != null) {
            attribute.elementEntity().getIdentifierMapping().forEachSelectable((i, c) -> orderBy.add(columnReference(collectionTableGroup, c)));
        } // else: a bag/set of values has no natural order

        // Element (and index) fetches resolve their columns against getRowColumns() while nested; eager
        // to-ones met on the way join inside the subquery and append their columns as segments (addSegment).
        rowSize = attribute.getRowColumns().size();
        fetches = creationState.visitNestedFetches(this);
        elementFetch = fetches.get(attribute.getElementDescriptor());
        indexFetch = attribute.getIndexDescriptor() == null ? null : fetches.get(attribute.getIndexDescriptor());

        List<Expression> rowRefs = new ArrayList<>(rowSize);
        List<JdbcMapping> jdbcMappings = new ArrayList<>(rowSize);
        List<Integer> scales = new ArrayList<>(rowSize);
        for (SelectableMapping column : attribute.getRowColumns()) {
            rowRefs.add(columnReference(collectionTableGroup, column));
            jdbcMappings.add(column.getJdbcMapping());
            scales.add(decimalScale(column));
        }
        for (Object extra : extras) {
            if (extra instanceof RowSegment segment) {
                TableGroup joined = fromClauseAccess.getTableGroup(segment.path);
                for (SelectableMapping column : segment.columns) {
                    rowRefs.add(columnReference(joined, column));
                    jdbcMappings.add(column.getJdbcMapping());
                    scales.add(decimalScale(column));
                }
            } else {
                NestedExpression nested = (NestedExpression) extra;
                rowRefs.add(nested.expression());
                jdbcMappings.add(nested.jdbcMapping());
                scales.add(null);
            }
        }
        rowJdbcMappings = jdbcMappings.toArray(JdbcMapping[]::new);
        rowScales = scales.toArray(Integer[]::new);

        QuerySpec subquery = new QuerySpec(false, 1);
        subquery.getFromClause().addRoot(collectionTableGroup);
        subquery.applyPredicate(join.getPredicate());
        subquery.getSelectClause().addSqlSelection(new SqlSelectionImpl(1, 0, dialect.aggregate(rowRefs, orderBy), false));
        SelectStatement aggregate = new SelectStatement(subquery);

        MultisetCollectionFetch enclosing = enclosingMultiset(fetchParent);
        if (enclosing == null) {
            aggregateSelection = sqlAstCreationState.getSqlExpressionResolver().resolveSqlSelection(
                    aggregate,
                    dialect.rowsType().getJavaTypeDescriptor(),
                    fetchParent,
                    sqlAstCreationState.getCreationContext().getTypeConfiguration());
        } else {
            // nested multiset: this subquery is one column of the enclosing ROW (a record[] inside the record)
            int position = enclosing.addNested(aggregate, dialect.rowsType());
            aggregateSelection = new SqlSelectionImpl(-1, position, dialect.rowsType().getJavaTypeDescriptor(), aggregate, true);
        }
    }

    /** The multiset fetch that {@code parent} is (transitively) an element part of, if any. */
    static MultisetCollectionFetch enclosingMultiset(FetchParent parent) {
        for (FetchParent p = parent; p instanceof Fetch fetch; p = fetch.getFetchParent()) {
            if (p instanceof MultisetCollectionFetch multiset) {
                return multiset;
            }
            if (p.hasJoinFetches() && p instanceof org.hibernate.sql.results.graph.collection.CollectionResultGraphNode) {
                return null; // a joined collection in between: not inside the multiset's ROW
            }
        }
        return null;
    }

    int addNested(Expression expression, JdbcMapping jdbcMapping) {
        int position = rowSize++;
        extras.add(new NestedExpression(expression, jdbcMapping));
        return position;
    }

    /** Declared scale of a decimal column, else the default Hibernate's DDL uses; null for other types. */
    private static Integer decimalScale(SelectableMapping column) {
        return switch (column.getJdbcMapping().getJdbcType().getDdlTypeCode()) {
            case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> column.getScale() != null ? column.getScale() : org.hibernate.engine.jdbc.Size.DEFAULT_SCALE;
            default -> null;
        };
    }

    private static ColumnReference columnReference(TableGroup tableGroup, SelectableMapping column) {
        return new ColumnReference(tableGroup.resolveTableReference(column.getContainingTableExpression()), column);
    }

    /** Reserves ROW positions for an eager to-one joined inside the subquery (called while nested fetches are visited). */
    RowSegment addSegment(NavigablePath path, EntityMappingType entity, FetchParent enclosing) {
        RowSegment segment = new RowSegment(path, entity, rowSize, enclosing);
        rowSize += segment.columns.size();
        extras.add(segment);
        return segment;
    }

    RowSegment segmentAt(NavigablePath path) {
        for (Object extra : extras) {
            if (extra instanceof RowSegment segment && segment.path.equals(path)) {
                return segment;
            }
        }
        return null;
    }

    @Override
    public FetchTiming getTiming() {
        // not joined into the owner's rows: keeps the SQM converter's bag/collection-join bookkeeping out of the way
        return FetchTiming.DELAYED;
    }

    @Override
    public boolean hasTableGroup() {
        return false;
    }

    @Override
    public ImmutableFetchList getFetches() {
        return fetches;
    }

    @Override
    public Fetch findFetch(Fetchable fetchable) {
        return fetches.get(fetchable);
    }

    @Override
    public NavigablePath resolveNavigablePath(Fetchable fetchable) {
        return getNavigablePath().append(fetchable.getFetchableName());
    }

    @Override
    public CollectionInitializer<?> createInitializer(InitializerParent<?> parent, AssemblerCreationState creationState) {
        return new MultisetCollectionInitializer(getNavigablePath(), getFetchedMapping(), parent, collectionKeyResult,
                aggregateSelection, rowJdbcMappings, rowScales, dialect, elementFetch, indexFetch, creationState);
    }

    @Override
    public JavaType<?> getResultJavaType() {
        return getFetchedMapping().getJavaType();
    }

    @Override
    public void collectValueIndexesToCache(BitSet valueIndexes) {
        if (collectionKeyResult != null) {
            collectionKeyResult.collectValueIndexesToCache(valueIndexes);
        }
        valueIndexes.set(aggregateSelection.getValuesArrayPosition());
    }
}
