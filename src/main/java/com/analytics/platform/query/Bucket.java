package com.analytics.platform.query;

/** Time-bucket granularity. The SQL function comes from this enum, never from user input. */
public enum Bucket {
    HOUR("toStartOfHour"),
    DAY("toStartOfDay");

    private final String sqlFunction;

    Bucket(String sqlFunction) {
        this.sqlFunction = sqlFunction;
    }

    public String sqlFunction() {
        return sqlFunction;
    }

    public static Bucket parse(String value) {
        for (Bucket b : values()) {
            if (b.name().equalsIgnoreCase(value)) {
                return b;
            }
        }
        throw new IllegalArgumentException("bucket must be 'hour' or 'day'");
    }
}
