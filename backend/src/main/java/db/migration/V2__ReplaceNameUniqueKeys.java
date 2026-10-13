package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class V2__ReplaceNameUniqueKeys extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        replaceNameUniqueKey(context.getConnection(), "ZG_XZ_MANAGE", "UK_ZG_XZ_MANAGE_SCID");
        replaceNameUniqueKey(context.getConnection(), "ZG_XZ_MANAGE_TARGET", "UK_ZG_XZ_MANAGE_TARGET_SCID");
    }

    private void replaceNameUniqueKey(Connection connection, String tableName, String scidIndexName) throws SQLException {
        TableReference table = resolveTable(connection, tableName);
        for (String indexName : uniqueSingleColumnIndexes(connection, table, "NAME_")) {
            dropUniqueConstraint(connection, table, indexName);
        }
        if (uniqueSingleColumnIndexes(connection, table, "SCID_").isEmpty()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE UNIQUE INDEX " + scidIndexName + " ON " + table.name + " (SCID_)");
            }
        }
    }

    private TableReference resolveTable(Connection connection, String expectedTableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getTables(connection.getCatalog(), null, "%", null)) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                if (expectedTableName.equalsIgnoreCase(tableName)) {
                    return new TableReference(tableName, resultSet.getString("TABLE_SCHEM"));
                }
            }
        }
        return new TableReference(expectedTableName, null);
    }

    private Set<String> uniqueSingleColumnIndexes(Connection connection, TableReference table, String columnName)
            throws SQLException {
        Map<String, Set<String>> indexColumns = new LinkedHashMap<String, Set<String>>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getIndexInfo(connection.getCatalog(), table.schema, table.name, true, false)) {
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                String indexedColumn = resultSet.getString("COLUMN_NAME");
                if (indexName == null || indexedColumn == null || resultSet.getBoolean("NON_UNIQUE")) {
                    continue;
                }
                Set<String> columns = indexColumns.get(indexName);
                if (columns == null) {
                    columns = new LinkedHashSet<String>();
                    indexColumns.put(indexName, columns);
                }
                columns.add(indexedColumn.toUpperCase(Locale.ROOT));
            }
        }

        Set<String> matchedIndexes = new LinkedHashSet<String>();
        for (Map.Entry<String, Set<String>> entry : indexColumns.entrySet()) {
            if (entry.getValue().size() == 1 && entry.getValue().contains(columnName)) {
                matchedIndexes.add(entry.getKey());
            }
        }
        return matchedIndexes;
    }

    private void dropUniqueConstraint(Connection connection, TableReference table, String indexName) throws SQLException {
        if ("H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            String constraintName = h2UniqueConstraintName(connection, table, indexName);
            if (constraintName == null) {
                throw new SQLException("未找到索引 " + indexName + " 对应的唯一约束。");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table.name + " DROP CONSTRAINT \"" + constraintName + "\"");
            }
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX " + indexName + " ON " + table.name);
        }
    }

    private String h2UniqueConstraintName(Connection connection, TableReference table, String indexName)
            throws SQLException {
        String sql = "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CONSTRAINTS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'UNIQUE' AND UNIQUE_INDEX_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.schema);
            statement.setString(2, table.name);
            statement.setString(3, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static final class TableReference {
        private final String name;
        private final String schema;

        private TableReference(String name, String schema) {
            this.name = name;
            this.schema = schema;
        }
    }
}
