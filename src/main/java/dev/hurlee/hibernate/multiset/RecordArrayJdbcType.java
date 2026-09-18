package dev.hurlee.hibernate.multiset;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.JdbcType;

/** Read-only {@link JdbcType} for a PostgreSQL {@code record[]} received in binary format: extracts {@code Object[][]}. */
public class RecordArrayJdbcType implements JdbcType {

    public static final RecordArrayJdbcType INSTANCE = new RecordArrayJdbcType();

    @Override
    public int getJdbcTypeCode() {
        return Types.OTHER;
    }

    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new BasicBinder<>(javaType, this) {
            @Override
            protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options) {
                throw new UnsupportedOperationException("record[] is read-only");
            }

            @Override
            protected void doBind(CallableStatement st, X value, String name, WrapperOptions options) {
                throw new UnsupportedOperationException("record[] is read-only");
            }
        };
    }

    @Override
    public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
        return new BasicExtractor<>(javaType, this) {
            @Override
            protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
                return decode(rs.getBytes(paramIndex), options);
            }

            @Override
            protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
                return decode(statement.getBytes(index), options);
            }

            @Override
            protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
                return decode(statement.getBytes(name), options);
            }

            private X decode(byte[] raw, WrapperOptions options) {
                return raw == null ? null : javaType.wrap(PgBinaryRecordArray.decodeRecordArray(raw), options);
            }
        };
    }
}
