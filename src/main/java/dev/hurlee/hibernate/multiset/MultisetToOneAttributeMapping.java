package dev.hurlee.hibernate.multiset;

import org.hibernate.engine.FetchTiming;
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.results.graph.DomainResultCreationState;
import org.hibernate.sql.results.graph.Fetch;
import org.hibernate.sql.results.graph.FetchParent;
import org.hibernate.sql.results.graph.entity.EntityFetch;

/**
 * {@link ToOneAttributeMapping} that, when fetched eagerly from inside a multiset element, joins
 * the target entity inside the multiset subquery and folds its columns into the {@code ROW}, so
 * {@code Order -> lines -> product} still loads in one statement.
 */
public class MultisetToOneAttributeMapping extends ToOneAttributeMapping {

    public MultisetToOneAttributeMapping(ToOneAttributeMapping original) {
        super(original);
    }

    @Override
    public EntityFetch generateFetch(
            FetchParent fetchParent,
            NavigablePath fetchablePath,
            FetchTiming fetchTiming,
            boolean selected,
            String resultVariable,
            DomainResultCreationState creationState) {
        MultisetCollectionFetch multiset = fetchTiming == FetchTiming.IMMEDIATE ? MultisetCollectionFetch.enclosingMultiset(fetchParent) : null;
        if (multiset == null) {
            return super.generateFetch(fetchParent, fetchablePath, fetchTiming, selected, resultVariable, creationState);
        }
        RowSegment segment = multiset.addSegment(fetchablePath, getAssociatedEntityMappingType(), currentNestingParent(fetchParent, multiset));
        return creationState.withNestedFetchParent(segment, np ->
                super.generateFetch(fetchParent, fetchablePath, fetchTiming, true, resultVariable, creationState));
    }

    /** Nesting parent currently in effect for {@code fetchParent}: the nearest segment, else the multiset fetch itself. */
    private static FetchParent currentNestingParent(FetchParent fetchParent, MultisetCollectionFetch multiset) {
        for (FetchParent p = fetchParent; p instanceof Fetch fetch && p != multiset; p = fetch.getFetchParent()) {
            RowSegment segment = multiset.segmentAt(p.getNavigablePath());
            if (segment != null) {
                return segment;
            }
        }
        return multiset;
    }
}
