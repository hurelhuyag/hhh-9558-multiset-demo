package dev.hurlee.hibernate.multiset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.hibernate.metamodel.mapping.ModelPart;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.ast.spi.SqlAstCreationContext;
import org.hibernate.sql.results.graph.AssemblerCreationState;
import org.hibernate.sql.results.graph.FetchParent;
import org.hibernate.sql.results.graph.Initializer;
import org.hibernate.sql.results.graph.InitializerParent;
import org.hibernate.sql.results.graph.InitializerProducer;

/**
 * {@link AssemblerCreationState} for the element entity's initializer tree. Initializers created
 * through it are kept here instead of being registered with the row reader, so that the
 * {@link MultisetCollectionInitializer} can drive them once per decoded record.
 */
class NestedAssemblerCreationState implements AssemblerCreationState {

    /**
     * Ids at or above this value belong to nested initializers and are stored by
     * {@link MultisetRowProcessingState}; the row reader's data array only covers registered ones.
     */
    static final int NESTED_ID_BASE = 1 << 20;
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger(NESTED_ID_BASE);

    private final AssemblerCreationState delegate;
    private final Map<NavigablePath, Initializer<?>> byPath = new HashMap<>();
    final List<Initializer<?>> initializers = new ArrayList<>();

    NestedAssemblerCreationState(AssemblerCreationState delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean isDynamicInstantiation() {
        return delegate.isDynamicInstantiation();
    }

    @Override
    public boolean containsMultipleCollectionFetches() {
        return delegate.containsMultipleCollectionFetches();
    }

    @Override
    public int acquireInitializerId() {
        return NEXT_ID.getAndIncrement();
    }

    @Override
    public Initializer<?> resolveInitializer(NavigablePath navigablePath, ModelPart fetchedModelPart, Supplier<Initializer<?>> producer) {
        Initializer<?> existing = byPath.get(navigablePath);
        if (existing != null) {
            return existing;
        }
        return register(navigablePath, producer.get());
    }

    @Override
    public <P extends FetchParent> Initializer<?> resolveInitializer(P resultGraphNode, InitializerParent<?> parent, InitializerProducer<P> producer) {
        Initializer<?> existing = byPath.get(resultGraphNode.getNavigablePath());
        if (existing != null) {
            return existing;
        }
        return register(resultGraphNode.getNavigablePath(), producer.createInitializer(resultGraphNode, parent, this));
    }

    private Initializer<?> register(NavigablePath path, Initializer<?> initializer) {
        byPath.put(path, initializer);
        initializers.add(initializer);
        return initializer;
    }

    @Override
    public SqlAstCreationContext getSqlAstCreationContext() {
        return delegate.getSqlAstCreationContext();
    }
}
