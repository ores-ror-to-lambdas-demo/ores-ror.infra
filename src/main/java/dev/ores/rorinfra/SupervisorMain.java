package dev.ores.rorinfra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

public final class SupervisorMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private SupervisorMain() {}

    public static void main(String[] args) throws Exception {
        final Settings settings = Settings.fromEnv();
        final Source source = Source.newBuilder(
            "ruby",
            Files.readString(settings.handlerPath, StandardCharsets.UTF_8),
            settings.handlerPath.getFileName().toString()
        ).cached(true).build();
        final Router router = Router.load(settings.routeContractPath);
        final HttpSupport httpSupport = new HttpSupport(settings.dataApiUrl, settings.dataApiToken);
        final IsolateSupervisor supervisor = new IsolateSupervisor(settings, source, httpSupport);
        final ExecutorService ingressPool = Executors.newFixedThreadPool(
            Math.max(2, settings.maxIsolates * settings.maxConcurrency),
            namedThreads("ror-ingress")
        );

        final HttpServer server = HttpServer.create(
            new InetSocketAddress(settings.bindHost, settings.port),
            128
        );
        server.setExecutor(ingressPool);
        server.createContext("/", exchange -> handle(exchange, router, supervisor, settings));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            ingressPool.shutdown();
            supervisor.close();
        }, "ror-shutdown"));

        server.start();
        System.out.println("ores-ror supervisor listening on http://" + settings.bindHost + ":" + settings.port);
    }

    private static void handle(
        HttpExchange exchange,
        Router router,
        IsolateSupervisor supervisor,
        Settings settings
    ) throws IOException {
        try {
            final RouteMatch match = router.match(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath()
            );
            if (match == null) {
                send(exchange, 404, JSON.createObjectNode().put("error", "route not found"));
                return;
            }

            final byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (bodyBytes.length > MAX_BODY_BYTES) {
                send(exchange, 413, JSON.createObjectNode().put("error", "request body too large"));
                return;
            }

            final String requestId = safeRequestId(exchange.getRequestHeaders().getFirst("x-request-id"));
            exchange.getResponseHeaders().set("x-request-id", requestId);
            final ObjectNode envelope = JSON.createObjectNode();
            envelope.put("request_id", requestId);
            envelope.put("route", match.name);
            envelope.put("method", exchange.getRequestMethod());
            envelope.put("data_api_base_url", settings.dataApiUrl);
            final ObjectNode params = envelope.putObject("params");
            match.params.forEach(params::put);
            final ObjectNode query = envelope.putObject("query");
            parseQuery(exchange.getRequestURI().getRawQuery()).forEach(query::put);

            if (bodyBytes.length > 0) {
                try {
                    final JsonNode body = JSON.readTree(bodyBytes);
                    envelope.set("body", body == null ? JSON.nullNode() : body);
                } catch (IOException malformedJson) {
                    throw new IllegalArgumentException("request body must be valid JSON");
                }
            }

            final JsonNode result = supervisor.invoke(envelope);
            final int status = result.path("status").asInt(result.path("ok").asBoolean(false) ? 200 : 500);
            if (result.path("body").isMissingNode()) {
                send(exchange, status, result);
            } else {
                send(exchange, status, result.path("body"));
            }
        } catch (RejectedExecutionException error) {
            send(exchange, 503, JSON.createObjectNode().put("error", "all isolates are saturated"));
        } catch (TimeoutException error) {
            send(exchange, 504, JSON.createObjectNode().put("error", "isolate execution timed out"));
        } catch (IllegalArgumentException error) {
            send(exchange, 400, JSON.createObjectNode().put("error", error.getMessage()));
        } catch (Exception error) {
            send(exchange, 500, JSON.createObjectNode().put("error", safeMessage(error)));
        } finally {
            exchange.close();
        }
    }

    private static String safeRequestId(String candidate) {
        if (candidate != null && candidate.matches("[A-Za-z0-9._:-]{1,128}")) {
            return candidate;
        }
        return "ores-request-" + UUID.randomUUID();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        final Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            final String[] parts = pair.split("=", 2);
            final String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            final String value = parts.length == 2
                ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                : "";
            if (result.size() >= 64) {
                throw new IllegalArgumentException("too many query parameters");
            }
            result.put(key, value);
        }
        return result;
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        final byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("x-content-type-options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static ThreadFactory namedThreads(String prefix) {
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private static String safeMessage(Throwable error) {
        final Throwable root = error instanceof ExecutionException && error.getCause() != null
            ? error.getCause() : error;
        final String message = root.getMessage();
        final String value = message == null || message.isBlank()
            ? root.getClass().getSimpleName() : message;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    static final class Settings {
        final String bindHost;
        final int port;
        final Path handlerPath;
        final Path routeContractPath;
        final String dataApiUrl;
        final String dataApiToken;
        final int minIsolates;
        final int maxIsolates;
        final int maxConcurrency;
        final long maxAgeMillis;
        final long idleMillis;
        final long drainMillis;

        private Settings(
            String bindHost,
            int port,
            Path handlerPath,
            Path routeContractPath,
            String dataApiUrl,
            String dataApiToken,
            int minIsolates,
            int maxIsolates,
            int maxConcurrency,
            long maxAgeMillis,
            long idleMillis,
            long drainMillis
        ) {
            this.bindHost = bindHost;
            this.port = port;
            this.handlerPath = handlerPath;
            this.routeContractPath = routeContractPath;
            this.dataApiUrl = dataApiUrl;
            this.dataApiToken = dataApiToken;
            this.minIsolates = minIsolates;
            this.maxIsolates = maxIsolates;
            this.maxConcurrency = maxConcurrency;
            this.maxAgeMillis = maxAgeMillis;
            this.idleMillis = idleMillis;
            this.drainMillis = drainMillis;
        }

        static Settings fromEnv() {
            final int concurrency = boundedInt("ISOLATE_MAX_CONCURRENCY", 5, 1, 5);
            final int min = boundedInt("MIN_ISOLATES", 1, 1, 32);
            final int max = boundedInt("MAX_ISOLATES", 4, min, 64);
            final String dataApiUrl = requiredEnv("DATA_API_URL").replaceAll("/$", "");
            final URI dataUri = URI.create(dataApiUrl);
            if (!List.of("http", "https").contains(dataUri.getScheme())) {
                throw new IllegalArgumentException("DATA_API_URL must use http or https");
            }
            return new Settings(
                env("BIND_HOST", "127.0.0.1"),
                boundedInt("PORT", 8080, 1, 65535),
                Path.of(env("HANDLER_PATH", "../ores-ror.rb/graal/handler.rb")).toAbsolutePath().normalize(),
                Path.of(env("ROUTE_CONTRACT_PATH", "../ores-ror.rb/graal/routes.json")).toAbsolutePath().normalize(),
                dataApiUrl,
                env("DATA_API_TOKEN", ""),
                min,
                max,
                concurrency,
                TimeUnit.SECONDS.toMillis(boundedInt("ISOLATE_MAX_AGE_SECONDS", 1800, 60, 1800)),
                TimeUnit.SECONDS.toMillis(boundedInt("ISOLATE_IDLE_SECONDS", 300, 30, 300)),
                TimeUnit.SECONDS.toMillis(boundedInt("ISOLATE_DRAIN_SECONDS", 30, 1, 300))
            );
        }

        private static int boundedInt(String name, int fallback, int min, int max) {
            final int value = Integer.parseInt(env(name, Integer.toString(fallback)));
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            }
            return value;
        }

        private static String requiredEnv(String name) {
            final String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("missing required environment variable " + name);
            }
            return value;
        }

        private static String env(String name, String fallback) {
            final String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    static final class IsolateSupervisor implements AutoCloseable {
        private final Settings settings;
        private final Source source;
        private final HttpSupport support;
        private final List<RubyIsolate> isolates = new ArrayList<>();
        private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
            namedThreads("ror-maintenance")
        );
        private long nextId;
        private boolean closed;

        IsolateSupervisor(Settings settings, Source source, HttpSupport support) {
            this.settings = settings;
            this.source = source;
            this.support = support;
            synchronized (this) {
                ensureMinimumLocked();
            }
            maintenance.scheduleAtFixedRate(this::maintainSafely, 1, 1, TimeUnit.SECONDS);
        }

        JsonNode invoke(ObjectNode envelope) throws Exception {
            final RubyIsolate isolate;
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("supervisor is closed");
                }
                maintainLocked(System.currentTimeMillis());
                isolate = selectLocked();
            }
            return isolate.submit(envelope).get(15, TimeUnit.SECONDS);
        }

        private RubyIsolate selectLocked() {
            final List<RubyIsolate> accepting = isolates.stream()
                .filter(RubyIsolate::isAccepting)
                .sorted(Comparator.comparingInt(RubyIsolate::load))
                .toList();
            if (accepting.isEmpty()) {
                final RubyIsolate created = createLocked();
                isolates.add(created);
                return created;
            }

            final RubyIsolate leastLoaded = accepting.get(0);
            if (leastLoaded.load() >= settings.maxConcurrency && accepting.size() < settings.maxIsolates) {
                final RubyIsolate created = createLocked();
                isolates.add(created);
                return created;
            }
            return leastLoaded;
        }

        private void maintainSafely() {
            try {
                synchronized (this) {
                    if (!closed) {
                        maintainLocked(System.currentTimeMillis());
                    }
                }
            } catch (Throwable error) {
                System.err.println("isolate maintenance failed: " + safeMessage(error));
            }
        }

        private void maintainLocked(long now) {
            int idleRetireBudget = Math.max(0, acceptingCountLocked() - settings.minIsolates);
            for (RubyIsolate isolate : isolates) {
                if (!isolate.isAccepting()) continue;
                if (isolate.exceededMaxAge(now)) {
                    isolate.beginRetirement();
                } else if (idleRetireBudget > 0 && isolate.exceededIdle(now)) {
                    isolate.beginRetirement();
                    idleRetireBudget -= 1;
                }
            }

            ensureMinimumLocked();

            final List<RubyIsolate> removable = new ArrayList<>();
            for (RubyIsolate isolate : isolates) {
                if (isolate.isRetiring() && isolate.load() == 0) {
                    isolate.close();
                    removable.add(isolate);
                }
            }
            isolates.removeAll(removable);
            ensureMinimumLocked();
        }

        private long acceptingCountLocked() {
            return isolates.stream().filter(RubyIsolate::isAccepting).count();
        }

        private void ensureMinimumLocked() {
            while (!closed && acceptingCountLocked() < settings.minIsolates
                && acceptingCountLocked() < settings.maxIsolates) {
                isolates.add(createLocked());
            }
        }

        private RubyIsolate createLocked() {
            nextId += 1;
            return new RubyIsolate("ruby-isolate-" + nextId, settings, source, support);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            maintenance.shutdownNow();
            for (RubyIsolate isolate : isolates) {
                isolate.beginRetirement();
                isolate.closeAfterDrain(settings.drainMillis);
            }
            isolates.clear();
        }
    }

    static final class RubyIsolate implements AutoCloseable {
        private final String id;
        private final Settings settings;
        private final Source source;
        private final HttpSupport support;
        private final Engine engine;
        private final ThreadPoolExecutor pool;
        private final long createdAt = System.currentTimeMillis();
        private final AtomicLong lastUsedAt = new AtomicLong(createdAt);
        private final AtomicInteger active = new AtomicInteger();
        private volatile boolean accepting = true;
        private volatile boolean closed;

        RubyIsolate(String id, Settings settings, Source source, HttpSupport support) {
            this.id = id;
            this.settings = settings;
            this.source = source;
            this.support = support;
            this.engine = Engine.newBuilder("ruby")
                .sandbox(SandboxPolicy.UNTRUSTED)
                .spawnIsolate(true)
                .option("engine.MaxIsolateMemory", "256MB")
                .build();
            this.pool = new ThreadPoolExecutor(
                settings.maxConcurrency,
                settings.maxConcurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.maxConcurrency * 8),
                namedThreads(id),
                new ThreadPoolExecutor.AbortPolicy()
            );
        }

        CompletableFuture<JsonNode> submit(ObjectNode envelope) {
            if (!accepting || closed) {
                throw new RejectedExecutionException(id + " is retiring");
            }
            lastUsedAt.set(System.currentTimeMillis());
            final CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pool.execute(() -> {
                active.incrementAndGet();
                lastUsedAt.set(System.currentTimeMillis());
                try {
                    future.complete(invokeInFreshContext(envelope));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                } finally {
                    lastUsedAt.set(System.currentTimeMillis());
                    active.decrementAndGet();
                }
            });
            return future;
        }

        private JsonNode invokeInFreshContext(ObjectNode envelope) throws Exception {
            try (Context context = Context.newBuilder("ruby")
                .engine(engine)
                .sandbox(SandboxPolicy.UNTRUSTED)
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.UNTRUSTED)
                .allowHostClassLookup(name -> false)
                .allowHostClassLoading(false)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowIO(IOAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowInnerContextOptions(false)
                .option("sandbox.MaxHeapMemory", "96MB")
                .option("sandbox.MaxCPUTime", "5s")
                .option("sandbox.MaxASTDepth", "128")
                .option("sandbox.MaxThreads", "1")
                .option("sandbox.MaxOutputStreamSize", "1MB")
                .option("sandbox.MaxErrorStreamSize", "1MB")
                .build()) {
                support.install(context);
                context.eval(source);
                final Value handler = context.eval("ruby", "method(:handler)");
                if (!handler.canExecute()) {
                    throw new IllegalStateException("Ruby handler is not executable");
                }
                final Value value = handler.execute(JSON.writeValueAsString(envelope));
                if (!value.isString()) {
                    throw new IllegalStateException("json-string-v1 handler must return a string");
                }
                return JSON.readTree(value.asString());
            }
        }

        int load() {
            return active.get() + pool.getQueue().size();
        }

        boolean isAccepting() {
            return accepting && !closed;
        }

        boolean isRetiring() {
            return !accepting && !closed;
        }

        boolean exceededMaxAge(long now) {
            return now - createdAt >= settings.maxAgeMillis;
        }

        boolean exceededIdle(long now) {
            return active.get() == 0 && pool.getQueue().isEmpty() && now - lastUsedAt.get() >= settings.idleMillis;
        }

        void beginRetirement() {
            accepting = false;
        }

        void closeAfterDrain(long drainMillis) {
            accepting = false;
            pool.shutdown();
            try {
                if (!pool.awaitTermination(drainMillis, TimeUnit.MILLISECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
            closeEngine();
        }

        @Override
        public void close() {
            accepting = false;
            pool.shutdown();
            if (load() != 0) {
                throw new IllegalStateException("cannot close isolate with queued or active requests");
            }
            closeEngine();
        }

        private synchronized void closeEngine() {
            if (closed) return;
            closed = true;
            engine.close();
        }
    }

    static final class HttpSupport {
        private final URI baseUri;
        private final String basePrefix;
        private final String bearerToken;
        private final java.net.http.HttpClient client;

        HttpSupport(String dataApiUrl, String bearerToken) {
            this.baseUri = URI.create(dataApiUrl);
            this.basePrefix = dataApiUrl.endsWith("/") ? dataApiUrl : dataApiUrl + "/";
            this.bearerToken = bearerToken;
            this.client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .build();
        }

        void install(Context context) {
            context.getBindings("ruby").putMember("gs_support_version", "gs-support-v1");
            context.getBindings("ruby").putMember(
                "gs_http",
                (org.graalvm.polyglot.proxy.ProxyExecutable) this::httpCall
            );
        }

        private Object httpCall(Value... args) {
            try {
                if (args.length != 1 || !args[0].isString()) {
                    throw new IllegalArgumentException("gs_http expects one JSON string");
                }
                final JsonNode request = JSON.readTree(args[0].asString());
                final String method = request.path("method").asText("GET").toUpperCase();
                if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").contains(method)) {
                    throw new IllegalArgumentException("unsupported HTTP method");
                }
                final URI uri = URI.create(request.path("url").asText());
                final String rendered = uri.toString();
                if (!(rendered.equals(baseUri.toString()) || rendered.startsWith(basePrefix))) {
                    throw new IllegalArgumentException("HTTP URL is outside the Data API grant");
                }

                final String body = request.path("body").isMissingNode() ? "" : request.path("body").asText("");
                if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                    throw new IllegalArgumentException("HTTP body too large");
                }

                final java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(Math.min(30_000, Math.max(1, request.path("timeout_ms").asLong(10_000)))))
                    .header("accept", "application/json")
                    .header("content-type", "application/json");
                if (!bearerToken.isBlank()) {
                    builder.header("authorization", "Bearer " + bearerToken);
                }
                final java.net.http.HttpRequest.BodyPublisher publisher = body.isEmpty()
                    ? java.net.http.HttpRequest.BodyPublishers.noBody()
                    : java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
                builder.method(method, publisher);

                final java.net.http.HttpResponse<byte[]> response = client.send(
                    builder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray()
                );
                if (response.body().length > MAX_BODY_BYTES) {
                    throw new IllegalStateException("HTTP response too large");
                }
                final ObjectNode result = JSON.createObjectNode();
                result.put("ok", true);
                result.put("status", response.statusCode());
                result.put("body", new String(response.body(), StandardCharsets.UTF_8));
                return JSON.writeValueAsString(result);
            } catch (Exception error) {
                final ObjectNode result = JSON.createObjectNode();
                result.put("ok", false);
                result.put("error", safeMessage(error));
                try {
                    return JSON.writeValueAsString(result);
                } catch (Exception impossible) {
                    return "{\"ok\":false,\"error\":\"serialization failure\"}";
                }
            }
        }
    }

    static final class Router {
        private final List<Route> routes;

        private Router(List<Route> routes) {
            this.routes = routes;
        }

        static Router load(Path path) throws IOException {
            final JsonNode root = JSON.readTree(Files.readAllBytes(path));
            if (!"ores-ror-routes-v1".equals(root.path("version").asText())) {
                throw new IllegalArgumentException("unsupported route contract version");
            }
            final List<Route> routes = new ArrayList<>();
            for (JsonNode node : root.path("routes")) {
                routes.add(Route.compile(
                    node.path("name").asText(),
                    node.path("method").asText(),
                    node.path("path").asText()
                ));
            }
            return new Router(List.copyOf(routes));
        }

        RouteMatch match(String method, String path) {
            for (Route route : routes) {
                final RouteMatch match = route.match(method, path);
                if (match != null) return match;
            }
            return null;
        }
    }

    static final class Route {
        private final String name;
        private final String method;
        private final java.util.regex.Pattern pattern;
        private final List<String> paramNames;

        private Route(String name, String method, java.util.regex.Pattern pattern, List<String> paramNames) {
            this.name = name;
            this.method = method;
            this.pattern = pattern;
            this.paramNames = paramNames;
        }

        static Route compile(String name, String method, String template) {
            if (name.isBlank() || method.isBlank() || !template.startsWith("/")) {
                throw new IllegalArgumentException("invalid route contract entry");
            }
            final StringBuilder regex = new StringBuilder("^");
            final List<String> names = new ArrayList<>();
            for (String segment : template.split("/", -1)) {
                if (segment.isEmpty()) continue;
                regex.append("/");
                if (segment.startsWith(":")) {
                    names.add(segment.substring(1));
                    regex.append("([A-Za-z0-9_-]{1,128})");
                } else {
                    regex.append(java.util.regex.Pattern.quote(segment));
                }
            }
            regex.append("$");
            return new Route(name, method.toUpperCase(), java.util.regex.Pattern.compile(regex.toString()), List.copyOf(names));
        }

        RouteMatch match(String candidateMethod, String candidatePath) {
            if (!method.equalsIgnoreCase(candidateMethod)) return null;
            final java.util.regex.Matcher matcher = pattern.matcher(candidatePath);
            if (!matcher.matches()) return null;
            final Map<String, String> params = new HashMap<>();
            for (int index = 0; index < paramNames.size(); index += 1) {
                params.put(paramNames.get(index), matcher.group(index + 1));
            }
            return new RouteMatch(name, params);
        }
    }

    static final class RouteMatch {
        final String name;
        final Map<String, String> params;

        RouteMatch(String name, Map<String, String> params) {
            this.name = name;
            this.params = Map.copyOf(params);
        }
    }
}
