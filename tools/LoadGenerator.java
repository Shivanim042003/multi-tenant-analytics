import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reproducible multi-tenant load generator (no dependencies):  java tools/LoadGenerator.java [--key=value ...]
 *
 *   --base=http://localhost:8080   --tenants=50   --duration=900   --warmup=30
 *   --mode=mixed|ingest|query      --ingest-threads=4   --query-threads=16   --batch-size=100
 *   --ingest-rate=0 (events/sec across all ingest threads; 0 = unthrottled)
 *   --seed=42   --zipf=1.1 (0 = uniform)   --anchor=2026-10-01T00:00:00Z (end of every query window)
 *   --key-prefix=dev-key-   --out=benchmark-results
 *
 * Query workload: tenant ~ Zipf over --tenants, query shape ~ Zipf over 25 shapes
 * (5 endpoints x 5 windows: 1h, 6h, 24h, 7d, 30d, all ending at --anchor). Windows are fixed, so repeated
 * (tenant, shape) pairs are identical requests and can be served from the cache.
 * Percentiles and throughput are computed after the warm-up period only.
 */
public class LoadGenerator {

    static final String[] TYPES = {"page_view", "click", "signup", "purchase", "refund"};
    static final int[] CUM_WEIGHT = {60, 85, 92, 99, 100};

    static final AtomicLong acceptedEvents = new AtomicLong();
    static final AtomicLong ingestErrors = new AtomicLong();
    static final AtomicLong queryOk = new AtomicLong();
    static final AtomicLong queryErrors = new AtomicLong();

    static final class LongBuf {
        long[] a = new long[1024];
        int n;
        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }
    }

    static final class Zipf {
        final double[] cdf;
        Zipf(int n, double s) {
            cdf = new double[n];
            double sum = 0;
            for (int i = 1; i <= n; i++) sum += 1.0 / Math.pow(i, s);
            double acc = 0;
            for (int i = 1; i <= n; i++) {
                acc += (1.0 / Math.pow(i, s)) / sum;
                cdf[i - 1] = acc;
            }
        }
        int sample(Random r) {
            double u = r.nextDouble();
            int lo = 0, hi = cdf.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cdf[mid] < u) lo = mid + 1; else hi = mid;
            }
            return lo;
        }
    }

    public static void main(String[] a) throws Exception {
        Map<String, String> args = new HashMap<>();
        for (String s : a) {
            if (s.startsWith("--") && s.contains("=")) args.put(s.substring(2, s.indexOf('=')), s.substring(s.indexOf('=') + 1));
        }
        final String base = args.getOrDefault("base", "http://localhost:8080");
        final int tenants = Integer.parseInt(args.getOrDefault("tenants", "50"));
        final int duration = Integer.parseInt(args.getOrDefault("duration", "900"));
        final int warmup = Integer.parseInt(args.getOrDefault("warmup", "30"));
        final String mode = args.getOrDefault("mode", "mixed");
        final int ingestThreads = mode.equals("query") ? 0 : Integer.parseInt(args.getOrDefault("ingest-threads", "4"));
        final int queryThreads = mode.equals("ingest") ? 0 : Integer.parseInt(args.getOrDefault("query-threads", "16"));
        final int batchSize = Integer.parseInt(args.getOrDefault("batch-size", "100"));
        final int ingestRate = Integer.parseInt(args.getOrDefault("ingest-rate", "0"));
        final long seed = Long.parseLong(args.getOrDefault("seed", "42"));
        final double zipfS = Double.parseDouble(args.getOrDefault("zipf", "1.1"));
        final String keyPrefix = args.getOrDefault("key-prefix", "dev-key-");
        final Instant anchor = args.containsKey("anchor") ? Instant.parse(args.get("anchor"))
                : Instant.now().truncatedTo(ChronoUnit.HOURS);
        final String outDir = args.getOrDefault("out", "benchmark-results");

        final List<String> shapes = buildShapes(anchor);
        final Zipf tenantZipf = new Zipf(tenants, zipfS);
        final Zipf shapeZipf = new Zipf(shapes.size(), zipfS);

        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();

        System.out.printf("mode=%s tenants=%d duration=%ds warmup=%ds ingestThreads=%d queryThreads=%d zipf=%.2f seed=%d anchor=%s%n",
                mode, tenants, duration, warmup, ingestThreads, queryThreads, zipfS, seed, anchor);

        long startNs = System.nanoTime();
        final long measureStartNs = startNs + warmup * 1_000_000_000L;
        final long endNs = startNs + duration * 1_000_000_000L;

        List<Thread> threads = new ArrayList<>();
        List<LongBuf> ingestLat = new ArrayList<>();
        List<LongBuf> queryLat = new ArrayList<>();
        final AtomicLong measuredEvents = new AtomicLong();

        for (int i = 0; i < ingestThreads; i++) {
            final int idx = i;
            final LongBuf buf = new LongBuf();
            ingestLat.add(buf);
            threads.add(new Thread(() -> {
                Random r = new Random(seed + idx);
                double perThreadRate = ingestRate > 0 ? (double) ingestRate / ingestThreads : 0;
                long nextSend = System.nanoTime();
                while (System.nanoTime() < endNs) {
                    if (perThreadRate > 0) {
                        long wait = nextSend - System.nanoTime();
                        if (wait > 0) sleepNs(wait);
                        nextSend += (long) (batchSize / perThreadRate * 1e9);
                    }
                    int tenant = tenantZipf.sample(r) + 1;
                    String body = batchBody(r, batchSize);
                    long t0 = System.nanoTime();
                    boolean ok = post(http, base + "/api/v1/events/batch", keyPrefix + tenantId(tenant), body);
                    long t1 = System.nanoTime();
                    if (t0 >= measureStartNs) {
                        if (ok) { buf.add(t1 - t0); measuredEvents.addAndGet(batchSize); acceptedEvents.addAndGet(batchSize); }
                        else ingestErrors.incrementAndGet();
                    }
                }
            }, "ingest-" + i));
        }

        for (int i = 0; i < queryThreads; i++) {
            final int idx = i;
            final LongBuf buf = new LongBuf();
            queryLat.add(buf);
            threads.add(new Thread(() -> {
                Random r = new Random(seed + 1000 + idx);
                while (System.nanoTime() < endNs) {
                    int tenant = tenantZipf.sample(r) + 1;
                    String path = shapes.get(shapeZipf.sample(r));
                    long t0 = System.nanoTime();
                    boolean ok = get(http, base + path, keyPrefix + tenantId(tenant));
                    long t1 = System.nanoTime();
                    if (t0 >= measureStartNs) {
                        if (ok) { buf.add(t1 - t0); queryOk.incrementAndGet(); }
                        else queryErrors.incrementAndGet();
                    }
                }
            }, "query-" + i));
        }

        // server-side counters at the start of the measurement window
        sleepNs(Math.max(0, measureStartNs - System.nanoTime()));
        double hit0 = metric(http, base, "analytics.cache?tag=result:hit");
        double miss0 = metric(http, base, "analytics.cache?tag=result:miss");
        double cacheErr0 = metric(http, base, "analytics.cache?tag=result:error");
        double published0 = metric(http, base, "analytics.events.published");
        double consumed0 = metric(http, base, "analytics.events.consumed");

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        double measuredSeconds = (endNs - measureStartNs) / 1e9;
        double hit1 = metric(http, base, "analytics.cache?tag=result:hit");
        double miss1 = metric(http, base, "analytics.cache?tag=result:miss");
        double cacheErr1 = metric(http, base, "analytics.cache?tag=result:error");
        double published1 = metric(http, base, "analytics.events.published");

        // wait for the consumer to drain what was published
        long drainStart = System.nanoTime();
        double consumed1 = metric(http, base, "analytics.events.consumed");
        while (consumed1 - consumed0 < published1 - published0 && System.nanoTime() - drainStart < 120_000_000_000L) {
            sleepNs(1_000_000_000L);
            consumed1 = metric(http, base, "analytics.events.consumed");
        }
        double drainSeconds = (System.nanoTime() - drainStart) / 1e9;

        long[] ing = merge(ingestLat);
        long[] qry = merge(queryLat);
        double hits = hit1 - hit0, misses = miss1 - miss0;
        double hitRate = (hits + misses) > 0 ? hits / (hits + misses) : Double.NaN;

        String json = String.format(Locale.ROOT, """
                {
                  "config": {"mode":"%s","tenants":%d,"durationSec":%d,"warmupSec":%d,"ingestThreads":%d,"queryThreads":%d,
                             "batchSize":%d,"ingestRateTarget":%d,"seed":%d,"zipf":%.2f,"anchor":"%s","startedAt":"%s"},
                  "environment": {"java":"%s","os":"%s","cpus":%d},
                  "ingest": {"acceptedEvents":%d,"eventsPerSec":%.1f,"batchErrors":%d,
                             "batchLatencyMs": {"p50":%s,"p95":%s,"p99":%s}},
                  "pipeline": {"publishedEvents":%.0f,"consumedEventsDuringWindow":%.0f,"drainSecondsAfterLoad":%.1f},
                  "query": {"ok":%d,"errors":%d,"queriesPerSec":%.1f,"latencyMs": {"p50":%s,"p95":%s,"p99":%s}},
                  "cache": {"hits":%.0f,"misses":%.0f,"errors":%.0f,"hitRate":%s}
                }
                """,
                mode, tenants, duration, warmup, ingestThreads, queryThreads, batchSize, ingestRate, seed, zipfS, anchor,
                Instant.now(), System.getProperty("java.version"), System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors(),
                measuredEvents.get(), measuredEvents.get() / measuredSeconds, ingestErrors.get(),
                j(pct(ing, 50)), j(pct(ing, 95)), j(pct(ing, 99)),
                published1 - published0, consumed1 - consumed0, drainSeconds,
                queryOk.get(), queryErrors.get(), queryOk.get() / measuredSeconds, j(pct(qry, 50)), j(pct(qry, 95)), j(pct(qry, 99)),
                hits, misses, cacheErr1 - cacheErr0, Double.isNaN(hitRate) ? "null" : String.format(Locale.ROOT, "%.4f", hitRate));

        System.out.println(json);
        Files.createDirectories(Path.of(outDir));
        Path out = Path.of(outDir, "run-" + Instant.now().toString().replace(':', '-') + ".json");
        Files.writeString(out, json);
        System.out.println("Saved " + out);
    }

    // ---------- workload ----------

    static List<String> buildShapes(Instant anchor) {
        long[] hours = {1, 6, 24, 24 * 7, 24 * 30};
        List<String> shapes = new ArrayList<>();
        for (long h : hours) {
            String range = "from=" + anchor.minus(Duration.ofHours(h)) + "&to=" + anchor;
            String bucket = h <= 24 ? "hour" : "day";
            shapes.add("/api/v1/analytics/count?" + range);
            shapes.add("/api/v1/analytics/by-type?" + range);
            shapes.add("/api/v1/analytics/timeseries?" + range + "&bucket=" + bucket);
            shapes.add("/api/v1/analytics/revenue?" + range);
            shapes.add("/api/v1/analytics/top-users?" + range + "&limit=10");
        }
        return shapes;
    }

    static String tenantId(int n) {
        return String.format("tenant-%03d", n);
    }

    static String batchBody(Random r, int size) {
        StringBuilder sb = new StringBuilder(size * 220).append('[');
        Instant now = Instant.now();
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(',');
            int w = r.nextInt(100), t = 0;
            while (w >= CUM_WEIGHT[t]) t++;
            String type = TYPES[t];
            String amount = type.equals("purchase") ? String.format(Locale.ROOT, "%.2f", 1 + r.nextInt(50000) / 100.0) : "null";
            sb.append("{\"eventId\":\"").append(new UUID(r.nextLong(), r.nextLong()))
                    .append("\",\"eventType\":\"").append(type)
                    .append("\",\"timestamp\":\"").append(now)
                    .append("\",\"userId\":\"user-").append(r.nextInt(50000))
                    .append("\",\"source\":\"web\",\"amount\":").append(amount)
                    .append(",\"properties\":{\"country\":\"US\"},\"schemaVersion\":1}");
        }
        return sb.append(']').toString();
    }

    // ---------- http / stats ----------

    static boolean post(HttpClient http, String url, String key, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("X-API-Key", key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 202;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean get(HttpClient http, String url, String key) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("X-API-Key", key).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    static double metric(HttpClient http, String base, String nameAndTag) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/actuator/metrics/" + nameAndTag))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return 0;
            Matcher m = Pattern.compile("\"statistic\":\"COUNT\",\"value\":([0-9.eE+-]+)").matcher(res.body());
            return m.find() ? Double.parseDouble(m.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    static long[] merge(List<LongBuf> bufs) {
        int total = 0;
        for (LongBuf b : bufs) total += b.n;
        long[] all = new long[total];
        int pos = 0;
        for (LongBuf b : bufs) { System.arraycopy(b.a, 0, all, pos, b.n); pos += b.n; }
        Arrays.sort(all);
        return all;
    }

    static double pct(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) return Double.NaN;
        int idx = (int) Math.ceil(p / 100.0 * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, Math.min(idx, sortedNanos.length - 1))] / 1e6;
    }

    static String j(double v) {
        return Double.isNaN(v) ? "null" : String.format(Locale.ROOT, "%.2f", v);
    }

    static void sleepNs(long ns) {
        try { Thread.sleep(ns / 1_000_000, (int) (ns % 1_000_000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
