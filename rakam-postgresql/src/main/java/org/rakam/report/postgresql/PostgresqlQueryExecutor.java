package org.rakam.report.postgresql;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import org.apache.commons.dbcp2.BasicDataSource;
import org.rakam.analysis.postgresql.PostgresqlConfig;
import org.rakam.collection.SchemaField;
import org.rakam.report.QueryError;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryExecutor;
import org.rakam.report.QueryResult;
import org.rakam.report.QueryStats;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.rakam.analysis.postgresql.PostgresqlSchemaMetastore.fromSql;

/**
 * Created by buremba <Burak Emre Kabakcı> on 06/04/15 00:48.
 */
public class PostgresqlQueryExecutor implements QueryExecutor {
    private final BasicDataSource connectionPool;
    private static final ExecutorService QUERY_EXECUTOR = new ThreadPoolExecutor(0, 50, 120L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadFactoryBuilder()
            .setNameFormat("postgresql-query-executor")
            .setUncaughtExceptionHandler((t, e) -> e.printStackTrace()).build());

    @Inject
    public PostgresqlQueryExecutor(PostgresqlConfig config) {
        connectionPool = new BasicDataSource();
        connectionPool.setUsername(config.getUsername());
        connectionPool.setPassword(config.getPassword());
        connectionPool.setDriverClassName(org.postgresql.Driver.class.getName());
        connectionPool.setUrl("jdbc:postgresql://" + config.getHost() + ':' + config.getPort() + "/" + config.getDatabase());
        connectionPool.setInitialSize(3);
        connectionPool.setPoolPreparedStatements(true);
    }

    @Override
    public QueryExecution executeQuery(String sqlQuery) {
        return new PostgresqlQueryExecution(connectionPool, sqlQuery);
    }

    public static class PostgresqlQueryExecution implements QueryExecution {

        private final CompletableFuture<QueryResult> result;
        private final String query;

        public PostgresqlQueryExecution(BasicDataSource connectionPool, String sqlQuery) {
            this.query = sqlQuery;

            this.result = CompletableFuture.supplyAsync(() -> {
                try (Connection connection = connectionPool.getConnection()) {
                    return resultSetToQueryResult(connection.createStatement().executeQuery(sqlQuery));
                } catch (Exception e) {
                    QueryError error;
                    if(e.getCause() instanceof SQLException) {
                        SQLException cause = (SQLException) e.getCause();
                        error = new QueryError(cause.getMessage(), cause.getSQLState(), cause.getErrorCode());
                    } else {
                        error = new QueryError("Internal query execution error", null, 0);
                    }
                    return new PostgresqlQueryResult(error, null, null);
                }
            }, QUERY_EXECUTOR);
        }

        @Override
        public QueryStats currentStats() {
            if(result.isDone()) {
                return new QueryStats(100, "FINISHED", null, null, null, null, null, null);
            } else {
                return new QueryStats(0, "PROCESSING", null, null, null, null, null, null);
            }
        }

        @Override
        public boolean isFinished() {
            return result.isDone();
        }

        @Override
        public CompletableFuture<? extends QueryResult> getResult() {
            return result;
        }

        @Override
        public String getQuery() {
            return query;
        }
    }

    private static QueryResult resultSetToQueryResult(ResultSet resultSet) {
        List<SchemaField> columns;
        List<List<Object>> data;
        try {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            columns = new ArrayList<>(columnCount);
            for (int i = 1; i < columnCount+1; i++) {
                columns.add(new SchemaField(metaData.getColumnName(i), fromSql(metaData.getColumnType(i)), true));
            }

            ImmutableList.Builder<List<Object>> builder = ImmutableList.builder();
            while (resultSet.next()) {
                List<Object> rowBuilder = Arrays.asList(new Object[columnCount]);
                for (int i = 1; i < columnCount+1; i++) {
                    rowBuilder.set(i - 1, resultSet.getObject(i));
                }
                builder.add(rowBuilder);
            }
            data = builder.build();
            return new PostgresqlQueryResult(null, data, columns);
        } catch (SQLException e) {
            QueryError error = new QueryError(e.getMessage(), e.getSQLState(), e.getErrorCode());
            return new PostgresqlQueryResult(error, null, null);
        }
    }

    public static class PostgresqlQueryResult implements QueryResult {
        private final List<SchemaField> columns;
        private final List<List<Object>> data;
        private final QueryError error;

        public PostgresqlQueryResult(QueryError error, List<List<Object>> data, List<SchemaField> columns) {
            this.data = data;
            this.columns = columns;
            this.error = error;
        }

        @Override
        public QueryError getError() {
            return error;
        }

        @Override
        public boolean isFailed() {
            return error != null;
        }

        @Override
        public List<List<Object>> getResult() {
            return data;
        }

        @Override
        public List<? extends SchemaField> getMetadata() {
            return columns;
        }
    }
}