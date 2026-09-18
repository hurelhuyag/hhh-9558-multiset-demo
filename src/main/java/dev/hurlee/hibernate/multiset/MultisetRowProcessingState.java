package dev.hurlee.hibernate.multiset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.LockMode;
import org.hibernate.engine.spi.CollectionKey;
import org.hibernate.engine.spi.EntityHolder;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.spi.QueryParameterBindings;
import org.hibernate.resource.jdbc.spi.LogicalConnectionImplementor;
import org.hibernate.sql.exec.internal.BaseExecutionContext;
import org.hibernate.sql.exec.spi.Callback;
import org.hibernate.sql.results.graph.InitializerData;
import org.hibernate.sql.results.graph.entity.EntityFetch;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesSourceProcessingState;
import org.hibernate.sql.results.jdbc.spi.RowProcessingState;
import org.hibernate.sql.results.spi.RowReader;
import org.hibernate.type.descriptor.java.JavaType;

/**
 * {@link RowProcessingState} that serves the fields of one decoded {@code ROW(...)} to the element
 * entity's initializers (by position, see {@link MultisetPluralAttributeMapping#getRowColumns()}),
 * and delegates everything else to the owner's row state. It keeps the initializer data of the
 * nested initializer tree in its own map, because those initializers are not part of the
 * {@link RowReader}'s flat initializer list.
 * <p>
 * Modelled on {@code org.hibernate.sql.results.graph.embeddable.internal.NestedRowProcessingState}.
 */
public class MultisetRowProcessingState extends BaseExecutionContext implements RowProcessingState {

    private final RowProcessingState parent;
    private final JdbcMapping[] rowJdbcMappings;
    private final Integer[] rowScales;
    private final MultisetDialect dialect;
    private final Map<Integer, InitializerData> initializerData = new HashMap<>();
    private Object[] currentRow;

    public MultisetRowProcessingState(RowProcessingState parent, JdbcMapping[] rowJdbcMappings, Integer[] rowScales, MultisetDialect dialect) {
        super(parent.getSession());
        this.parent = parent;
        this.rowJdbcMappings = rowJdbcMappings;
        this.rowScales = rowScales;
        this.dialect = dialect;
    }

    public void setCurrentRow(Object[] row) {
        this.currentRow = row;
    }

    @Override
    public Object getJdbcValue(int position) {
        if (currentRow == null) {
            return null;
        }
        Object raw = currentRow[position];
        if (raw == null) {
            return null;
        }
        Object value = coerce(raw, rowJdbcMappings[position]);
        if (value instanceof BigDecimal decimal && rowScales[position] != null && decimal.scale() != rowScales[position]) {
            return decimal.setScale(rowScales[position], RoundingMode.UNNECESSARY); // JSON numbers carry no scale
        }
        return value;
    }

    /**
     * The binary record carries SQL types (int8 -> Long, timestamp -> LocalDateTime, ...) and
     * {@link JavaType#wrap} finishes the job. JSON is weaker: temporal values arrive as strings and a
     * nested multiset may arrive as a JSON string, so those are parsed here before wrapping.
     */
    private Object coerce(Object raw, JdbcMapping mapping) {
        if (raw instanceof String text) {
            if (dialect.isRowsType(mapping)) {
                return JsonRecordArrayJdbcType.parseRows(text);
            }
            JavaType<?> javaType = mapping.getJdbcJavaType();
            if (!CharSequence.class.isAssignableFrom(javaType.getJavaTypeClass())
                    && javaType.getJavaTypeClass() != Character.class) {
                if (mapping.getJdbcType().isTemporal() && text.length() > 10 && text.charAt(10) == ' ') {
                    text = text.substring(0, 10) + 'T' + text.substring(11); // 'yyyy-MM-dd HH:mm:ss[.ffffff]' -> ISO
                }
                return javaType.fromString(text);
            }
        }
        return mapping.getJdbcJavaType().wrap(raw, getSession());
    }

    @Override
    public <T extends InitializerData> T getInitializerData(int initializerId) {
        InitializerData own = initializerData.get(initializerId);
        // not ours: the owner (bidirectional back-reference) or, for a nested multiset, an enclosing level
        //noinspection unchecked
        return own != null ? (T) own : parent.getInitializerData(initializerId);
    }

    @Override
    public void setInitializerData(int initializerId, InitializerData state) {
        if (initializerId < NestedAssemblerCreationState.NESTED_ID_BASE) {
            parent.setInitializerData(initializerId, state);
        } else {
            initializerData.put(initializerId, state);
        }
    }

    @Override
    public RowProcessingState unwrap() {
        return parent;
    }

    // -- delegate the rest

    @Override
    public JdbcValuesSourceProcessingState getJdbcValuesSourceProcessingState() {
        return parent.getJdbcValuesSourceProcessingState();
    }

    @Override
    public LockMode determineEffectiveLockMode(String alias) {
        return parent.determineEffectiveLockMode(alias);
    }

    @Override
    public boolean needsResolveState() {
        return parent.needsResolveState();
    }

    @Override
    public RowReader<?> getRowReader() {
        return parent.getRowReader();
    }

    @Override
    public void registerNonExists(EntityFetch fetch) {
        parent.registerNonExists(fetch);
    }

    @Override
    public boolean isQueryCacheHit() {
        return parent.isQueryCacheHit();
    }

    @Override
    public void finishRowProcessing(boolean wasAdded) {
        parent.finishRowProcessing(wasAdded);
    }

    @Override
    public QueryOptions getQueryOptions() {
        return parent.getQueryOptions();
    }

    @Override
    public QueryParameterBindings getQueryParameterBindings() {
        return parent.getQueryParameterBindings();
    }

    @Override
    public boolean isScrollResult() {
        return parent.isScrollResult();
    }

    @Override
    public Callback getCallback() {
        return parent.getCallback();
    }

    @Override
    public boolean hasCallbackActions() {
        return parent.hasCallbackActions();
    }

    @Override
    public CollectionKey getCollectionKey() {
        return parent.getCollectionKey();
    }

    @Override
    public Object getEntityInstance() {
        return parent.getEntityInstance();
    }

    @Override
    public Object getEntityId() {
        return parent.getEntityId();
    }

    @Override
    public String getEntityUniqueKeyAttributePath() {
        return parent.getEntityUniqueKeyAttributePath();
    }

    @Override
    public Object getEntityUniqueKey() {
        return parent.getEntityUniqueKey();
    }

    @Override
    public EntityMappingType getRootEntityDescriptor() {
        return parent.getRootEntityDescriptor();
    }

    @Override
    public void registerLoadingEntityHolder(EntityHolder holder) {
        parent.registerLoadingEntityHolder(holder);
    }

    @Override
    public void afterStatement(LogicalConnectionImplementor logicalConnection) {
        parent.afterStatement(logicalConnection);
    }

    @Override
    public boolean hasQueryExecutionToBeAddedToStatistics() {
        return parent.hasQueryExecutionToBeAddedToStatistics();
    }

    @Override
    public String getQueryIdentifier(String sql) {
        return parent.getQueryIdentifier(sql);
    }

    @Override
    public boolean upgradeLocks() {
        return parent.upgradeLocks();
    }
}
