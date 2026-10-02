import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates a large, deterministic dataset directly inside ClickHouse (INSERT ... SELECT FROM numbers()).
 * Same --seed/--rows/--tenants/--days/--anchor  =>  identical data.
 *
 *   java tools/SeedData.java --rows=10000000 --tenants=50 --days=30 --seed=42
 *   (add --anchor=2026-10-01T00:00:00Z to pin the end of the time range; default = now truncated to the hour)
 */
public class SeedData {

    public static void main(String[] a) throws Exception {
        Map<String, String> args = new HashMap<>();
        for (String s : a) {
            if (s.startsWith("--") && s.contains("=")) {
                args.put(s.substring(2, s.indexOf('=')), s.substring(s.indexOf('=') + 1));
            }
        }
        String url = args.getOrDefault("url", "http://localhost:8123");
        String db = args.getOrDefault("db", "analytics");
        String user = args.getOrDefault("user", "analytics");
        String password = args.getOrDefault("password", "analytics");
        long rows = Long.parseLong(args.getOrDefault("rows", "1000000"));
        long batch = Long.parseLong(args.getOrDefault("batch-rows", "1000000"));
        int tenants = Integer.parseInt(args.getOrDefault("tenants", "50"));
        int days = Integer.parseInt(args.getOrDefault("days", "30"));
        long seed = Long.parseLong(args.getOrDefault("seed", "42"));
        Instant anchor = args.containsKey("anchor")
                ? Instant.parse(args.get("anchor"))
                : Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        long anchorEpoch = anchor.getEpochSecond();
        long span = days * 86400L;

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        System.out.printf("Seeding %,d rows | tenants=%d days=%d seed=%d anchor=%s%n", rows, tenants, days, seed, anchor);
        long t0 = System.nanoTime();
        for (long offset = 0; offset < rows; offset += batch) {
            long count = Math.min(batch, rows - offset);
            String sql = insertSql(offset, count, tenants, span, seed, anchorEpoch);
            run(http, auth, url, db, sql);
            System.out.printf("  inserted %,d / %,d%n", offset + count, rows);
        }
        if (Boolean.parseBoolean(args.getOrDefault("optimize", "false"))) {
            System.out.println("OPTIMIZE TABLE events FINAL ...");
            run(http, auth, url, db, "OPTIMIZE TABLE events FINAL");
        }
        String total = run(http, auth, url, db, "SELECT count() FROM events").trim();
        System.out.printf("Done in %.1fs. events table now has %s rows.%n", (System.nanoTime() - t0) / 1e9, total);
        System.out.printf("Use --anchor=%s on the load generator so query windows line up with this data.%n", anchor);
    }

    static String insertSql(long offset, long count, int tenants, long span, long seed, long anchorEpoch) {
        String h = "concat(leftPad(lower(hex(cityHash64(number, %d, 8))), 16, '0'), leftPad(lower(hex(cityHash64(number, %d, 9))), 16, '0'))";
        h = String.format(h, seed, seed);
        return "INSERT INTO events (tenant_id, event_id, event_type, event_time, user_id, source, amount, properties, schema_version)\n"
                + "WITH " + h + " AS h, cityHash64(number, " + seed + ", 2) % 100 AS r\n"
                + "SELECT\n"
                + "  concat('tenant-', leftPad(toString(1 + cityHash64(number, " + seed + ", 1) % " + tenants + "), 3, '0')) AS tenant_id,\n"
                + "  toUUID(concat(substring(h, 1, 8), '-', substring(h, 9, 4), '-', substring(h, 13, 4), '-', substring(h, 17, 4), '-', substring(h, 21, 12))) AS event_id,\n"
                + "  multiIf(r < 60, 'page_view', r < 85, 'click', r < 92, 'signup', r < 99, 'purchase', 'refund') AS event_type,\n"
                + "  toDateTime64(toInt64(" + anchorEpoch + ") - toInt64(cityHash64(number, " + seed + ", 3) % " + span + "), 3, 'UTC') AS event_time,\n"
                + "  concat('user-', toString(cityHash64(number, " + seed + ", 4) % 50000)) AS user_id,\n"
                + "  arrayElement(['web', 'ios', 'android', 'api'], 1 + cityHash64(number, " + seed + ", 5) % 4) AS source,\n"
                + "  if(event_type = 'purchase', toDecimal64((100 + cityHash64(number, " + seed + ", 6) % 49900) / 100, 4), NULL) AS amount,\n"
                + "  concat('{\"country\":\"', arrayElement(['US', 'CA', 'GB', 'DE', 'IN'], 1 + cityHash64(number, " + seed + ", 7) % 5), '\"}') AS properties,\n"
                + "  toUInt16(1) AS schema_version\n"
                + "FROM numbers(" + offset + ", " + count + ")";
    }

    static String run(HttpClient http, String auth, String url, String db, String sql) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/?database=" + URLEncoder.encode(db, StandardCharsets.UTF_8)))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("ClickHouse HTTP " + res.statusCode() + ": " + res.body());
        }
        return res.body();
    }
}
