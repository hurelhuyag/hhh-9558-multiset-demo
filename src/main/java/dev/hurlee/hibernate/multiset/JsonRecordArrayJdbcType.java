package dev.hurlee.hibernate.multiset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.JdbcType;

/**
 * Read-only {@link JdbcType} for a {@code json_arrayagg(json_array(...))} value: a JSON array of
 * positional arrays, decoded into {@code Object[][]} (numbers as Integer/Long/BigDecimal, strings,
 * booleans, nulls; a nested array of arrays — a nested multiset — as {@code Object[][]}).
 */
public class JsonRecordArrayJdbcType implements JdbcType {

    public static final JsonRecordArrayJdbcType INSTANCE = new JsonRecordArrayJdbcType();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Override
    public int getJdbcTypeCode() {
        return Types.VARCHAR;
    }

    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new BasicBinder<>(javaType, this) {
            @Override
            protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options) {
                throw new UnsupportedOperationException("multiset JSON is read-only");
            }

            @Override
            protected void doBind(CallableStatement st, X value, String name, WrapperOptions options) {
                throw new UnsupportedOperationException("multiset JSON is read-only");
            }
        };
    }

    @Override
    public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
        return new BasicExtractor<>(javaType, this) {
            @Override
            protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
                return decode(rs.getString(paramIndex), options);
            }

            @Override
            protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
                return decode(statement.getString(index), options);
            }

            @Override
            protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
                return decode(statement.getString(name), options);
            }

            private X decode(String json, WrapperOptions options) {
                return json == null ? null : javaType.wrap(parseRows(json), options);
            }
        };
    }

    /** {@code [[1,"a"],[2,null]]} → {@code Object[][]}. */
    public static Object[][] parseRows(String json) {
        try {
            return rows(MAPPER.readTree(json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object[][] rows(JsonNode array) {
        Object[][] rows = new Object[array.size()][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = array.get(i).isNull() ? null : fields(array.get(i));
        }
        return rows;
    }

    private static Object[] fields(JsonNode row) {
        Object[] fields = new Object[row.size()];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = value(row.get(i));
        }
        return fields;
    }

    private static Object value(JsonNode node) {
        return switch (node.getNodeType()) {
            case NULL -> null;
            case NUMBER -> node.numberValue();
            case STRING -> node.textValue();
            case BOOLEAN -> node.booleanValue();
            case ARRAY -> rows(node); // nested multiset
            default -> throw new IllegalArgumentException("unexpected JSON value inside a multiset row: " + node);
        };
    }
}
