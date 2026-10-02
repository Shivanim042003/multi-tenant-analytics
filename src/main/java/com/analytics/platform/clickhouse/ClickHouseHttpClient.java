package com.analytics.platform.clickhouse;

import com.analytics.platform.config.AnalyticsProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Minimal ClickHouse HTTP client for writes and DDL. Inserts use FORMAT JSONEachRow, which keeps the
 * type mapping (UUID, DateTime64, Decimal) explicit and independent of JDBC driver quirks.
 * Reads go through JDBC (ClickHouseQueryRepository).
 */
@Component
public class ClickHouseHttpClient {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String database;
    private final String authHeader;

    public ClickHouseHttpClient(AnalyticsProperties props) {
        AnalyticsProperties.ClickHouse ch = props.clickhouse();
        this.baseUrl = ch.httpUrl().replaceAll("/+$", "");
        this.database = requireIdentifier(ch.database());
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((ch.username() + ":" + ch.password()).getBytes(StandardCharsets.UTF_8));
    }

    public String database() {
        return database;
    }

    /** Executes a statement (DDL etc.). withDatabase=false is used for CREATE DATABASE. */
    public String execute(String sql, boolean withDatabase) {
        String url = baseUrl + "/" + (withDatabase ? "?database=" + enc(database) : "");
        return send(url, sql);
    }

    public void insertJsonEachRow(String table, String ndjson) {
        requireIdentifier(table);
        String query = "INSERT INTO " + table + " FORMAT JSONEachRow";
        String url = baseUrl + "/?database=" + enc(database) + "&query=" + enc(query);
        send(url, ndjson);
    }

    private String send(String url, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String text = response.body() == null ? "" : response.body();
                throw new ClickHouseException("ClickHouse returned HTTP " + response.statusCode() + ": "
                        + text.substring(0, Math.min(500, text.length())));
            }
            return response.body();
        } catch (IOException e) {
            throw new ClickHouseException("ClickHouse request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClickHouseException("Interrupted talking to ClickHouse", e);
        }
    }

    static String requireIdentifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid identifier: " + value);
        }
        return value;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
