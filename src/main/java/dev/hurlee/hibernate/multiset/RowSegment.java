package dev.hurlee.hibernate.multiset;

import java.lang.reflect.Proxy;
import java.util.List;

import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.results.graph.AssemblerCreationState;
import org.hibernate.sql.results.graph.FetchParent;
import org.hibernate.sql.results.graph.Fetchable;
import org.hibernate.sql.results.graph.FetchableContainer;
import org.hibernate.sql.results.graph.Initializer;
import org.hibernate.sql.results.graph.InitializerParent;
import org.hibernate.sql.results.graph.internal.ImmutableFetchList;

/**
 * Nesting fetch parent for an eager to-one joined <em>inside</em> the multiset subquery: the target
 * entity's columns occupy positions {@code [offset, offset + columns.size())} of the {@code ROW}.
 * Column names not belonging to the target (e.g. the FK on the referring side) fall back to the
 * enclosing nesting parent, so {@code id} resolves to the right entity at every level.
 */
final class RowSegment implements FetchParent {

    final NavigablePath path;
    final EntityMappingType entity;
    final List<SelectableMapping> columns;
    final int offset;
    private final FetchParent enclosing;
    private final EntityMappingType indexResolvingType;

    RowSegment(NavigablePath path, EntityMappingType entity, int offset, FetchParent enclosing) {
        this.path = path;
        this.entity = entity;
        this.columns = RowColumns.of(entity);
        this.offset = offset;
        this.enclosing = enclosing;
        // SqlAstQueryPartProcessingStateImpl asks getReferencedMappingType().getSelectableIndex(name):
        // hand it the real entity type with that one default method intercepted
        this.indexResolvingType = (EntityMappingType) Proxy.newProxyInstance(
                RowSegment.class.getClassLoader(),
                new Class<?>[] { EntityMappingType.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getSelectableIndex") && args != null && args.length == 1) {
                        return getSelectableIndex((String) args[0]);
                    }
                    return method.invoke(entity, args);
                });
    }

    int getSelectableIndex(String selectableName) {
        int i = RowColumns.indexOf(columns, selectableName);
        return i != -1 ? offset + i : enclosing.getReferencedMappingType().getSelectableIndex(selectableName);
    }

    @Override
    public FetchableContainer getReferencedMappingContainer() {
        return entity;
    }

    @Override
    public FetchableContainer getReferencedMappingType() {
        return indexResolvingType;
    }

    @Override
    public NavigablePath getNavigablePath() {
        return path;
    }

    @Override
    public ImmutableFetchList getFetches() {
        return ImmutableFetchList.EMPTY;
    }

    @Override
    public org.hibernate.sql.results.graph.Fetch findFetch(Fetchable fetchable) {
        return null;
    }

    @Override
    public boolean hasJoinFetches() {
        return false;
    }

    @Override
    public boolean containsCollectionFetches() {
        return false;
    }

    @Override
    public org.hibernate.type.descriptor.java.JavaType<?> getResultJavaType() {
        return entity.getJavaType();
    }

    @Override
    public Initializer<?> createInitializer(InitializerParent<?> parent, AssemblerCreationState creationState) {
        throw new UnsupportedOperationException("RowSegment is only a nesting fetch parent");
    }
}
