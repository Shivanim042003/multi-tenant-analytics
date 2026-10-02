package com.analytics.platform.clickhouse;

public class ClickHouseException extends RuntimeException {
    public ClickHouseException(String message) {
        super(message);
    }

    public ClickHouseException(String message, Throwable cause) {
        super(message, cause);
    }
}
