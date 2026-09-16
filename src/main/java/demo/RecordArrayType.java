package demo;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.hibernate.HibernateException;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.usertype.ParameterizedType;
import org.hibernate.usertype.UserType;

/**
 * Hibernate {@link UserType} that reads a PostgreSQL {@code record[]} column (typically an
 * {@code array_agg(ROW(...))} {@code @Formula}) from the binary wire format and materialises each
 * record into a Java {@code record} via its canonical constructor:
 * <pre>
 *   &#64;Formula("(select array_agg(row(p.id, p.number) order by p.id) from phone p where p.user_id = id)")
 *   &#64;Type(value = RecordArrayType.class, parameters = &#64;Parameter(name = "record", value = "demo.User$PhoneView"))
 *   private List&lt;PhoneView&gt; phones;
 * </pre>
 * Fields are matched positionally: the i-th {@code ROW(...)} expression feeds the i-th record
 * component. The JDBC connection must be configured with
 * {@code binaryTransferEnable=2287&prepareThreshold=-1} (see {@link PgBinaryRecordArray}).
 * Read-only: SQL NULL (no child rows) becomes an empty list.
 */
@SuppressWarnings("rawtypes")
public class RecordArrayType implements UserType<List>, ParameterizedType {

    public static final String RECORD_PARAM = "record";

    private Class<? extends Record> recordClass;
    private Constructor<? extends Record> canonicalConstructor;
    private Class<?>[] componentTypes;

    @Override
    public void setParameterValues(Properties parameters) {
        String name = parameters.getProperty(RECORD_PARAM);
        if (name == null) {
            throw new HibernateException("RecordArrayType needs the '" + RECORD_PARAM + "' parameter (a java.lang.Record class name)");
        }
        try {
            recordClass = Class.forName(name, true, Thread.currentThread().getContextClassLoader()).asSubclass(Record.class);
            RecordComponent[] components = recordClass.getRecordComponents();
            componentTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                componentTypes[i] = components[i].getType();
            }
            canonicalConstructor = recordClass.getDeclaredConstructor(componentTypes);
            canonicalConstructor.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new HibernateException("cannot resolve record class " + name, e);
        }
    }

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<List> returnedClass() {
        return List.class;
    }

    @Override
    public List nullSafeGet(ResultSet rs, int position, WrapperOptions options) throws SQLException {
        byte[] raw = rs.getBytes(position);
        if (raw == null) {
            return List.of();
        }
        Object[][] rows = PgBinaryRecordArray.decodeRecordArray(raw);
        List<Record> result = new ArrayList<>(rows.length);
        for (Object[] row : rows) {
            result.add(row == null ? null : materialise(row));
        }
        return result;
    }

    private Record materialise(Object[] fields) {
        if (fields.length != componentTypes.length) {
            throw new HibernateException(recordClass.getSimpleName() + " has " + componentTypes.length
                    + " components but the ROW(...) has " + fields.length + " fields");
        }
        Object[] args = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            args[i] = coerce(fields[i], componentTypes[i]);
        }
        try {
            return canonicalConstructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new HibernateException("cannot instantiate " + recordClass.getName(), e);
        }
    }

    /** The wire carries the SQL type (int4 -> Integer, int8 -> Long, ...); widen to what the record declares. */
    private static Object coerce(Object value, Class<?> target) {
        if (value == null || target.isInstance(value)) {
            return value;
        }
        if (value instanceof Number n) {
            if (target == Long.class || target == long.class) return n.longValue();
            if (target == Integer.class || target == int.class) return n.intValue();
            if (target == Short.class || target == short.class) return n.shortValue();
            if (target == Double.class || target == double.class) return n.doubleValue();
            if (target == Float.class || target == float.class) return n.floatValue();
        }
        if (target == String.class) {
            return value.toString();
        }
        throw new HibernateException("cannot convert " + value.getClass().getName() + " to " + target.getName());
    }

    @Override
    public void nullSafeSet(PreparedStatement st, List value, int index, WrapperOptions options) {
        throw new UnsupportedOperationException("RecordArrayType is read-only (use it on a @Formula)");
    }

    @Override
    public boolean equals(List x, List y) {
        return x == null ? y == null : x.equals(y);
    }

    @Override
    public int hashCode(List x) {
        return x.hashCode();
    }

    @Override
    public List deepCopy(List value) {
        return value; // lists of immutable records: sharing is safe
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(List value) {
        return value == null ? null : new ArrayList<>(value);
    }

    @Override
    public List assemble(Serializable cached, Object owner) {
        return (List) cached;
    }
}
