package dev.hurlee.hibernate.multiset;

import org.hibernate.cache.spi.access.EntityDataAccess;
import org.hibernate.cache.spi.access.NaturalIdDataAccess;
import org.hibernate.FetchMode;
import org.hibernate.engine.spi.CascadeStyle;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.metamodel.model.domain.NavigableRole;
import org.hibernate.type.EntityType;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.ManagedMappingType;
import org.hibernate.metamodel.mapping.internal.MappingModelCreationHelper;
import org.hibernate.metamodel.mapping.internal.MappingModelCreationProcess;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.persister.entity.SingleTableEntityPersister;
import org.hibernate.property.access.spi.PropertyAccess;

/**
 * Entity persister that substitutes {@link MultisetPluralAttributeMapping} for collections annotated
 * with {@link MultisetFetch}. Uses the {@code mappingConverter} hook that
 * {@link MappingModelCreationHelper#buildPluralAttributeMapping} exposes for Hibernate Reactive.
 */
public class MultisetEntityPersister extends SingleTableEntityPersister {

    public MultisetEntityPersister(
            PersistentClass persistentClass,
            EntityDataAccess cacheAccessStrategy,
            NaturalIdDataAccess naturalIdRegionAccessStrategy,
            RuntimeModelCreationContext creationContext) {
        super(persistentClass, cacheAccessStrategy, naturalIdRegionAccessStrategy, creationContext);
    }

    @Override
    protected AttributeMapping buildPluralAttributeMapping(
            String attrName,
            int stateArrayPosition,
            int fetchableIndex,
            Property bootProperty,
            ManagedMappingType declaringType,
            PropertyAccess propertyAccess,
            CascadeStyle cascadeStyle,
            FetchMode fetchMode,
            MappingModelCreationProcess creationProcess) {
        if (!isMultisetFetched(declaringType.getJavaType().getJavaTypeClass(), attrName)) {
            return super.buildPluralAttributeMapping(attrName, stateArrayPosition, fetchableIndex, bootProperty,
                    declaringType, propertyAccess, cascadeStyle, fetchMode, creationProcess);
        }
        return MappingModelCreationHelper.buildPluralAttributeMapping(
                attrName, stateArrayPosition, fetchableIndex, bootProperty, declaringType, propertyAccess,
                cascadeStyle, fetchMode, creationProcess, MultisetPluralAttributeMapping::new);
    }

    /** Every to-one gets the multiset-aware mapping: any entity may be a multiset element. */
    @Override
    protected AttributeMapping buildSingularAssociationAttributeMapping(
            String attrName,
            NavigableRole navigableRole,
            int stateArrayPosition,
            int fetchableIndex,
            Property bootProperty,
            ManagedMappingType declaringType,
            org.hibernate.persister.entity.EntityPersister declaringEntityPersister,
            EntityType attrType,
            PropertyAccess propertyAccess,
            CascadeStyle cascadeStyle,
            MappingModelCreationProcess creationProcess) {
        return MappingModelCreationHelper.buildSingularAssociationAttributeMapping(
                attrName, navigableRole, stateArrayPosition, fetchableIndex, bootProperty, declaringType,
                declaringEntityPersister, attrType, propertyAccess, cascadeStyle, creationProcess,
                MultisetToOneAttributeMapping::new);
    }

    private static boolean isMultisetFetched(Class<?> type, String attributeName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(attributeName).isAnnotationPresent(MultisetFetch.class);
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        return false;
    }
}
