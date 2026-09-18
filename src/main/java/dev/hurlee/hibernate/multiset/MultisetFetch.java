package dev.hurlee.hibernate.multiset;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an eager {@code @OneToMany} to be fetched as a MULTISET: the whole collection is returned
 * as one {@code array_agg(ROW(...))} value in the owner's select list, so any number of collections
 * load in the same statement without a cartesian product (HHH-9558).
 * <p>
 * Requires {@link MultisetPersisterClassResolver} to be registered via
 * {@code hibernate.persister.resolver} and a PostgreSQL connection with
 * {@code binaryTransferEnable=2287&prepareThreshold=-1}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MultisetFetch {
}
