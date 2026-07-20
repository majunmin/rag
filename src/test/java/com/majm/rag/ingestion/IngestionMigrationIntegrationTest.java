package com.majm.rag.ingestion;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IngestionMigrationIntegrationTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/rag";
    private static final String USER = "rag";
    private static final String PASSWORD = "rag";

    @Test
    void v8RemovesPartialLegacyVectorsButPreservesCompletedDocuments() throws Exception {
        assumeTrue(canConnect(), "Postgres not reachable on localhost:5432");
        String schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");

        try {
            migrate(schema, MigrationVersion.fromVersion("7"));
            seedLegacyVectors(schema);

            migrate(schema, MigrationVersion.LATEST);

            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                assertThat(count(statement, schema, "FAILED")).isZero();
                assertThat(count(statement, schema, "DONE")).isEqualTo(1);
            }
        } finally {
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private void migrate(String schema, MigrationVersion target) {
        Flyway.configure()
            .dataSource(URL, USER, PASSWORD)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .target(target)
            .load()
            .migrate();
    }

    private void seedLegacyVectors(String schema) throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID doneId = UUID.randomUUID();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema + ", public");
            statement.execute("INSERT INTO knowledge_base (id, name) VALUES ('" + kbId + "', 'migration test')");
            statement.execute("""
                INSERT INTO document (id, knowledge_base_id, name, file_type, status)
                VALUES ('%s', '%s', 'failed.txt', 'TXT', 'FAILED'),
                       ('%s', '%s', 'done.txt', 'TXT', 'DONE')
                """.formatted(failedId, kbId, doneId, kbId));
            statement.execute(vectorInsert(failedId));
            statement.execute(vectorInsert(doneId));
        }
    }

    private String vectorInsert(UUID documentId) {
        return """
            INSERT INTO vector_store (id, content, metadata)
            VALUES ('%s', 'legacy', '{"document_id":"%s","chunk_index":0}'::jsonb)
            """.formatted(UUID.randomUUID(), documentId);
    }

    private int count(Statement statement, String schema, String status) throws Exception {
        try (ResultSet result = statement.executeQuery("""
            SELECT COUNT(*)
              FROM %s.vector_store v
              JOIN %s.document d ON d.id = v.document_id
             WHERE d.status = '%s'
            """.formatted(schema, schema, status))) {
            result.next();
            return result.getInt(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private boolean canConnect() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
