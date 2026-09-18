package dev.hurlee.hibernate.multiset;

import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.internal.StandardPersisterClassResolver;

/** Plugs {@link MultisetEntityPersister} in through the standard {@code hibernate.persister.resolver} service. */
public class MultisetPersisterClassResolver extends StandardPersisterClassResolver {

    @Override
    public Class<? extends EntityPersister> singleTableEntityPersister() {
        return MultisetEntityPersister.class;
    }
}
