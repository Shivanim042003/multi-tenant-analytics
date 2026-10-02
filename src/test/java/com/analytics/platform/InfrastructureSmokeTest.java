package com.analytics.platform;

import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class InfrastructureSmokeTest {

    @Container
    static ClickHouseContainer clickHouse =
            new ClickHouseContainer("clickhouse/clickhouse-server:25.8");

    @Test
    void clickHouseContainerStarts() {
        assertTrue(clickHouse.isRunning());
    }

    @Test
    void clickHouseAcceptsJdbcQuery() throws Exception {

        try (Connection connection = DriverManager.getConnection(
                clickHouse.getJdbcUrl(),
                clickHouse.getUsername(),
                clickHouse.getPassword());

             Statement statement = connection.createStatement();

             ResultSet resultSet = statement.executeQuery("SELECT 1")) {

            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }
}
