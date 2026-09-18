package dev.hurlee.hibernate.multiset;

import java.util.List;

import java.lang.reflect.Field;

import org.hibernate.engine.FetchTiming;
import org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter;
import org.hibernate.query.sqm.tree.from.SqmAttributeJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmJoin;
import org.hibernate.query.sqm.tree.select.SqmQueryPart;
import org.hibernate.query.sqm.tree.select.SqmQuerySpec;
import org.hibernate.sql.ast.spi.FromClauseAccess;
import org.hibernate.sql.ast.tree.from.AbstractTableGroup;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.metamodel.mapping.internal.EntityCollectionPart;
import org.hibernate.metamodel.mapping.internal.PluralAttributeMappingImpl;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.results.graph.DomainResultCreationState;
import org.hibernate.sql.results.graph.Fetch;
import org.hibernate.sql.results.graph.FetchParent;

/**
 * {@link PluralAttributeMappingImpl} whose eager fetch is a {@link MultisetCollectionFetch}. Also
 * defines the shape of the {@code ROW(...)}: the columns of the element entity, in
 * {@link EntityMappingType#forEachSelectable} order, so that nested column selections resolve
 * (via {@link #getSelectableIndex}) to positions inside the decoded record.
 */
public class MultisetPluralAttributeMapping extends PluralAttributeMappingImpl {

    private List<SelectableMapping> rowColumns;

    public MultisetPluralAttributeMapping(PluralAttributeMappingImpl original) {
        super(original);
    }

    /** The element's own columns of each {@code ROW(...)} (positions 0..n-1); eager to-ones append segments after them. */
    public List<SelectableMapping> getRowColumns() {
        if (rowColumns == null) {
            List<SelectableMapping> columns = new java.util.ArrayList<>();
            if (getElementDescriptor() instanceof EntityCollectionPart entityPart) {
                columns.addAll(RowColumns.of(entityPart.getAssociatedEntityMappingType()));
            } else {
                // @ElementCollection: basic value(s) or the embeddable's columns
                getElementDescriptor().forEachSelectable((i, selectable) -> RowColumns.add(columns, selectable));
            }
            if (getIndexDescriptor() != null) {
                getIndexDescriptor().forEachSelectable((i, selectable) -> RowColumns.add(columns, selectable));
            }
            rowColumns = List.copyOf(columns);
        }
        return rowColumns;
    }

    /** The element entity, or {@code null} for an {@code @ElementCollection}. */
    public EntityMappingType elementEntity() {
        return getElementDescriptor() instanceof EntityCollectionPart entityPart
                ? entityPart.getAssociatedEntityMappingType()
                : null;
    }

    /** Called by the SQL AST creation state while this fetch is the nesting fetch parent. */
    @Override
    public int getSelectableIndex(String selectableName) {
        return RowColumns.indexOf(getRowColumns(), selectableName);
    }

    /**
     * Any immediate fetch of this collection — {@code fetch = EAGER}, an entity graph or a fetch
     * profile — becomes a multiset fetch; an explicit HQL {@code join fetch} is honoured as a join.
     */
    @Override
    public Fetch generateFetch(
            FetchParent fetchParent,
            NavigablePath fetchablePath,
            FetchTiming fetchTiming,
            boolean selected,
            String resultVariable,
            DomainResultCreationState creationState) {
        if (fetchTiming != FetchTiming.IMMEDIATE) {
            return super.generateFetch(fetchParent, fetchablePath, fetchTiming, selected, resultVariable, creationState);
        }
        FromClauseAccess fromClauseAccess = creationState.getSqlAstCreationState().getFromClauseAccess();
        TableGroupJoin existingJoin = null;
        if (fromClauseAccess.findTableGroup(fetchablePath) != null) {
            if (isExplicitSqmJoin(fetchablePath, creationState)) {
                return super.generateFetch(fetchParent, fetchablePath, fetchTiming, selected, resultVariable, creationState);
            }
            // The SQM converter joins entity-graph / fetch-profile fetches before asking for the fetch:
            // take that join away from the owner and use it inside the multiset subquery instead.
            existingJoin = detachJoin(fromClauseAccess.getTableGroup(fetchParent.getNavigablePath()), fetchablePath);
        }
        boolean added = creationState.registerVisitedAssociationKey(getKeyDescriptor().getAssociationKey());
        try {
            return new MultisetCollectionFetch(fetchablePath, this, fetchParent, existingJoin, creationState);
        } finally {
            if (added) {
                creationState.removeVisitedAssociationKey(getKeyDescriptor().getAssociationKey());
            }
        }
    }

    /** True when the HQL/criteria query itself contains {@code join fetch <fetchablePath>}. */
    private static boolean isExplicitSqmJoin(NavigablePath fetchablePath, DomainResultCreationState creationState) {
        if (!(creationState instanceof BaseSqmToSqlAstConverter<?> converter)) {
            return false;
        }
        SqmQueryPart<?> part = converter.getSqmQueryPartStack().getCurrent();
        if (!(part instanceof SqmQuerySpec<?> spec)) {
            return false;
        }
        boolean[] found = { false };
        spec.getFromClause().visitRoots(root -> visitJoins(root, join -> {
            if (join instanceof SqmAttributeJoin<?, ?> attributeJoin && attributeJoin.isFetched()
                    && join.getNavigablePath().equals(fetchablePath)) {
                found[0] = true;
            }
        }));
        return found[0];
    }

    private static void visitJoins(SqmFrom<?, ?> from, java.util.function.Consumer<SqmJoin<?, ?>> consumer) {
        from.visitSqmJoins(join -> {
            consumer.accept(join);
            visitJoins(join, consumer);
        });
    }

    /**
     * Removes the join to {@code fetchablePath} from {@code owner}'s join list and returns it.
     * {@link AbstractTableGroup} exposes the list read-only, hence the reflection; a hibernate-core
     * implementation would simply not create the join for a MULTISET-style collection.
     */
    private static TableGroupJoin detachJoin(TableGroup owner, NavigablePath fetchablePath) {
        try {
            Field field = AbstractTableGroup.class.getDeclaredField("tableGroupJoins");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<TableGroupJoin> joins = (java.util.List<TableGroupJoin>) field.get(owner);
            for (java.util.Iterator<TableGroupJoin> it = joins.iterator(); it.hasNext(); ) {
                TableGroupJoin join = it.next();
                if (join.getJoinedGroup().getNavigablePath().equals(fetchablePath)) {
                    it.remove();
                    return join;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("no join to " + fetchablePath + " on " + owner.getNavigablePath());
    }
}
