package dev.hurlee.hibernate.multiset;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.AbstractClassJavaType;

/** {@link org.hibernate.type.descriptor.java.JavaType} for the decoded {@code Object[][]} rows of a {@code record[]}. */
public class RecordArrayJavaType extends AbstractClassJavaType<Object[][]> {

    public static final RecordArrayJavaType INSTANCE = new RecordArrayJavaType();

    public RecordArrayJavaType() {
        super(Object[][].class);
    }

    @Override
    public <X> X unwrap(Object[][] value, Class<X> type, WrapperOptions options) {
        return type.cast(value);
    }

    @Override
    public <X> Object[][] wrap(X value, WrapperOptions options) {
        return (Object[][]) value;
    }
}
