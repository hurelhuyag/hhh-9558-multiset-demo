package dev.hurlee.hibernate.multiset;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.CollectionKey;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.metamodel.CollectionClassification;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.ast.spi.SqlSelection;
import org.hibernate.sql.results.graph.AssemblerCreationState;
import org.hibernate.sql.results.graph.DomainResult;
import org.hibernate.sql.results.graph.DomainResultAssembler;
import org.hibernate.sql.results.graph.Fetch;
import org.hibernate.sql.results.graph.Initializer;
import org.hibernate.sql.results.graph.InitializerData;
import org.hibernate.sql.results.graph.InitializerParent;
import org.hibernate.sql.results.graph.collection.internal.AbstractCollectionInitializer;
import org.hibernate.sql.results.graph.collection.internal.AbstractNonJoinCollectionInitializer;
import org.hibernate.sql.results.graph.entity.internal.EntityInitializerImpl;
import org.hibernate.sql.results.internal.LoadingCollectionEntryImpl;
import org.hibernate.sql.results.jdbc.spi.RowProcessingState;

/**
 * Initialises a collection from one {@code record[]} value: for every decoded record the element
 * entity's (unregistered) initializer tree is driven through a {@link MultisetRowProcessingState},
 * exactly like the row reader drives top-level initializers, and the resulting managed entity is
 * added to a standard {@link LoadingCollectionEntryImpl}. Hibernate then finishes the collection
 * (inject state, {@code endRead}, persistence-context bookkeeping, cache) as it would for a join fetch.
 */
public class MultisetCollectionInitializer extends AbstractNonJoinCollectionInitializer<MultisetCollectionInitializer.Data> {

    private final SqlSelection aggregateSelection;
    private final JdbcMapping[] rowJdbcMappings;
    private final Integer[] rowScales;
    private final MultisetDialect dialect;
    private final DomainResultAssembler<?> elementAssembler;
    private final DomainResultAssembler<?> indexAssembler;
    private final CollectionClassification classification;
    private final int listIndexBase;
    private final Initializer<?>[] nestedRoots;
    private final Initializer<?>[] nestedResolveOrder;
    private final Initializer<?>[] nestedAll;

    public static class Data extends AbstractCollectionInitializer.CollectionInitializerData {
        final MultisetRowProcessingState nested;

        Data(RowProcessingState rowProcessingState, JdbcMapping[] rowJdbcMappings, Integer[] rowScales, MultisetDialect dialect) {
            super(rowProcessingState);
            nested = new MultisetRowProcessingState(rowProcessingState, rowJdbcMappings, rowScales, dialect);
        }

        CollectionKey key() {
            return collectionKey;
        }
    }

    public MultisetCollectionInitializer(
            NavigablePath fetchedPath,
            PluralAttributeMapping fetchedMapping,
            InitializerParent<?> parent,
            DomainResult<?> collectionKeyResult,
            SqlSelection aggregateSelection,
            JdbcMapping[] rowJdbcMappings,
            Integer[] rowScales,
            MultisetDialect dialect,
            Fetch elementFetch,
            Fetch indexFetch,
            AssemblerCreationState creationState) {
        super(fetchedPath, fetchedMapping, parent, collectionKeyResult, false, creationState);
        this.aggregateSelection = aggregateSelection;
        this.rowJdbcMappings = rowJdbcMappings;
        this.rowScales = rowScales;
        this.dialect = dialect;
        this.classification = fetchedMapping.getMappedType().getCollectionSemantics().getCollectionClassification();
        this.listIndexBase = fetchedMapping.getIndexMetadata() == null ? 0 : fetchedMapping.getIndexMetadata().getListIndexBase();

        NestedAssemblerCreationState nestedCreationState = new NestedAssemblerCreationState(creationState);
        this.elementAssembler = elementFetch.createAssembler(this, nestedCreationState);
        this.indexAssembler = indexFetch == null ? null : indexFetch.createAssembler(this, nestedCreationState);
        this.nestedAll = nestedCreationState.initializers.toArray(Initializer[]::new);
        this.nestedRoots = nestedCreationState.initializers.stream().filter(i -> i.getParent() == this).toArray(Initializer[]::new);
        // same ordering rule as org.hibernate.sql.results.internal.InitializersList: entity initializers resolve first
        List<Initializer<?>> order = new ArrayList<>();
        for (Initializer<?> i : nestedAll) {
            if (i instanceof EntityInitializerImpl) {
                order.add(i);
            }
        }
        for (Initializer<?> i : nestedAll) {
            if (!(i instanceof EntityInitializerImpl)) {
                order.add(i);
            }
        }
        this.nestedResolveOrder = order.toArray(Initializer[]::new);
    }

    @Override
    protected InitializerData createInitializerData(RowProcessingState rowProcessingState) {
        return new Data(rowProcessingState, rowJdbcMappings, rowScales, dialect);
    }

    @Override
    public void startLoading(RowProcessingState rowProcessingState) {
        super.startLoading(rowProcessingState);
        Data data = getData(rowProcessingState);
        for (Initializer<?> root : nestedRoots) {
            root.startLoading(data.nested);
        }
    }

    @Override
    public void resolveInstance(Data data) {
        // resolves the key and finds/creates the PersistentCollection wrapper (registered as uninitialised)
        resolveInstance(data, false);
        PersistentCollection<?> collection = data.getCollectionInstance();
        if (collection == null || collection.wasInitialized()) {
            return;
        }
        RowProcessingState rowProcessingState = data.getRowProcessingState();
        PersistenceContext persistenceContext = rowProcessingState.getSession().getPersistenceContextInternal();
        CollectionKey key = data.key();
        if (persistenceContext.getLoadContexts().findLoadingCollectionEntry(key) != null) {
            return; // another initializer is already loading this collection
        }
        var loadingEntry = new LoadingCollectionEntryImpl(getCollectionAttributeMapping().getCollectionDescriptor(), this, key.getKey(), collection);
        rowProcessingState.getJdbcValuesSourceProcessingState().registerLoadingCollection(key, loadingEntry);

        Object[][] rows = (Object[][]) rowProcessingState.getJdbcValue(aggregateSelection);
        if (rows != null) {
            for (Object[] row : rows) {
                if (row != null) {
                    materialise(row, data.nested, loadingEntry);
                }
            }
        }
        data.nested.setCurrentRow(null);
    }

    @Override
    public void resolveInstance(Object instance, Data data) {
        resolveInstance(instance, data, false);
    }

    /**
     * Runs the element initializer tree over one record, the way {@code StandardRowReader} runs a row,
     * then records the element the way the built-in Bag/List/Set/Map initializers do.
     */
    @SuppressWarnings("unchecked")
    private void materialise(Object[] row, MultisetRowProcessingState nested, LoadingCollectionEntryImpl loadingEntry) {
        nested.setCurrentRow(row);
        for (Initializer<?> root : nestedRoots) {
            ((Initializer<InitializerData>) root).resolveKey(root.getData(nested));
        }
        for (Initializer<?> i : nestedResolveOrder) {
            InitializerData d = i.getData(nested);
            if (d.getState() == State.KEY_RESOLVED) {
                ((Initializer<InitializerData>) i).resolveInstance(d);
            }
        }
        for (Initializer<?> i : nestedAll) {
            InitializerData d = i.getData(nested);
            if (d.getState() == State.RESOLVED) {
                ((Initializer<InitializerData>) i).initializeInstance(d);
            }
        }
        Object element = elementAssembler.assemble(nested);
        Object index = indexAssembler == null ? null : indexAssembler.assemble(nested);
        for (Initializer<?> i : nestedAll) {
            ((Initializer<InitializerData>) i).finishUpRow(i.getData(nested));
        }
        if (element == null) {
            return; // not-found element: NotFoundAction.IGNORE semantics
        }
        loadingEntry.load(state -> {
            switch (classification) {
                case LIST, ARRAY -> {
                    int position = (Integer) index - listIndexBase;
                    for (int i = state.size(); i <= position; i++) {
                        state.add(null);
                    }
                    state.set(position, element);
                }
                case MAP, SORTED_MAP, ORDERED_MAP -> state.add(new Object[] { index, element });
                case ID_BAG -> throw new UnsupportedOperationException("@MultisetFetch does not support idbag collections");
                default -> state.add(element);
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void endLoading(Data data) {
        super.endLoading(data);
        for (Initializer<?> i : nestedAll) {
            InitializerData d = i.getData(data.nested);
            if (d != null) {
                ((Initializer<InitializerData>) i).endLoading(d);
            }
        }
    }

    @Override
    public void initializeInstanceFromParent(Object parentInstance, Data data) {
        Object instance = getInitializedPart().getValue(parentInstance);
        if (instance == null) {
            setMissing(data);
        } else {
            data.setCollectionInstance((PersistentCollection<?>) instance);
            data.setState(State.INITIALIZED);
        }
    }

    @Override
    public String toString() {
        return "MultisetCollectionInitializer(" + getNavigablePath() + ")";
    }
}
