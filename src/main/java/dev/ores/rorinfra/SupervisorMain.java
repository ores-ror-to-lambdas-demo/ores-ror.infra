package dev.ores.rorinfra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
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
import java.util.regex.Pattern;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

public final class SupervisorMain {
  static final ObjectMapper JSON = new ObjectMapper();
  static final int MAX_BODY = 1024 * 1024;

  private SupervisorMain() {}

  public static void main(String[] args) throws Exception {
    Settings settings = Settings.fromEnv();
    try (Cluster cluster = new Cluster(settings)) {
      ExecutorService ingress = Executors.newFixedThreadPool(
        Math.max(8, settings.unitCount() * settings.workersPerIsolate), named("ingress"));
      HttpServer server = HttpServer.create(new InetSocketAddress(settings.bindHost, settings.port), 256);
      server.setExecutor(ingress);
      server.createContext("/", exchange -> handle(exchange, cluster));
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        server.stop(1);
        ingress.shutdown();
        cluster.close();
      }, "ror-shutdown"));
      server.start();
      System.out.printf(
        "TruffleRuby/Graal listening on http://%s:%d granularity=%s isolates=%d contexts/isolate=1 threads/context=%d shared-engine=1%n",
        settings.bindHost,
        settings.port,
        settings.granularity,
        settings.unitCount(),
        settings.workersPerIsolate);
      Thread.currentThread().join();
    }
  }

  static void handle(HttpExchange exchange, Cluster cluster) throws IOException {
    try {
      byte[] input = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
      if (input.length > MAX_BODY) {
        sendError(exchange, 413, "request body too large");
        return;
      }

      ObjectNode request = JSON.createObjectNode();
      request.put("request_id", requestId(exchange.getRequestHeaders().getFirst("x-request-id")));
      request.put("method", exchange.getRequestMethod());
      request.put("path", exchange.getRequestURI().getPath());
      request.put("query_string", exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery());
      request.put("body", new String(input, StandardCharsets.UTF_8));
      ObjectNode headers = request.putObject("headers");
      String contentType = exchange.getRequestHeaders().getFirst("content-type");
      if (contentType != null) headers.put("content-type", contentType);

      JsonNode result = cluster.invoke(request);
      int status = result.path("status").asInt(500);
      result.path("headers").fields().forEachRemaining(header -> {
        if (header.getValue().isTextual()
            && !header.getKey().equalsIgnoreCase("content-length")
            && !header.getKey().equalsIgnoreCase("connection")) {
          exchange.getResponseHeaders().set(header.getKey(), header.getValue().asText());
        }
      });
      byte[] body = result.path("body").asText("").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    } catch (RejectedExecutionException error) {
      sendError(exchange, 503, "Graal isolate saturated or retiring");
    } catch (TimeoutException error) {
      sendError(exchange, 504, "Graal request timed out; isolate replaced");
    } catch (Exception error) {
      sendError(exchange, 500, safe(error));
    } finally {
      exchange.close();
    }
  }

  static void sendError(HttpExchange exchange, int status, String message) throws IOException {
    byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("error", message));
    exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  static final class RouteDef {
    final String verb;
    final String path;
    final String routeId;
    final String group;
    final Pattern pattern;

    RouteDef(String verb, String path, String routeId, String group) {
      this.verb = verb;
      this.path = path;
      this.routeId = routeId;
      this.group = group;
      this.pattern = Pattern.compile(routeRegex(path));
    }

    boolean matches(String method, String requestPath) {
      return verb.equalsIgnoreCase(method) && pattern.matcher(requestPath).matches();
    }

    static String routeRegex(String path) {
      StringBuilder out = new StringBuilder("^");
      String[] parts = path.split("/", -1);
      for (int i = 0; i < parts.length; i++) {
        if (i > 0) out.append('/');
        String part = parts[i];
        if (part.startsWith(":")) out.append("[^/]+");
        else out.append(Pattern.quote(part));
      }
      return out.append('$').toString();
    }
  }

  static final class UnitDef {
    final String kind;
    final String key;
    final String group;
    final List<String> routeIds;
    final Path unitPath;
    final String unitSource;

    UnitDef(String kind, String key, String group, List<String> routeIds, Path unitPath, String unitSource) {
      this.kind = kind;
      this.key = key;
      this.group = group;
      this.routeIds = List.copyOf(routeIds);
      this.unitPath = unitPath;
      this.unitSource = unitSource;
    }
  }

  static final class Settings {
    final String bindHost;
    final int port;
    final Path appRoot;
    final Path manifestPath;
    final String granularity;
    final String dataUrl;
    final String dataToken;
    final int workersPerIsolate;
    final long maxAgeMs;
    final long idleMs;
    final long drainMs;
    final long requestTimeoutMs;
    final String commonSource;
    final List<RouteDef> routes;
    final Map<String, UnitDef> units;

    Settings(
      String bindHost,
      int port,
      Path appRoot,
      Path manifestPath,
      String granularity,
      String dataUrl,
      String dataToken,
      int workersPerIsolate,
      long maxAgeMs,
      long idleMs,
      long drainMs,
      long requestTimeoutMs
    ) {
      this.bindHost = bindHost;
      this.port = port;
      this.appRoot = appRoot.toAbsolutePath().normalize();
      this.manifestPath = manifestPath.toAbsolutePath().normalize();
      this.granularity = granularity;
      this.dataUrl = dataUrl;
      this.dataToken = dataToken;
      this.workersPerIsolate = workersPerIsolate;
      this.maxAgeMs = maxAgeMs;
      this.idleMs = idleMs;
      this.drainMs = drainMs;
      this.requestTimeoutMs = requestTimeoutMs;
      if (!List.of("route", "group").contains(granularity)) {
        throw new IllegalArgumentException("ISOLATION_GRANULARITY must be route or group");
      }
      URI uri = URI.create(dataUrl);
      if (!List.of("http", "https").contains(uri.getScheme())) {
        throw new IllegalArgumentException("DATA_API_URL must use http or https");
      }

      try {
        JsonNode manifest = JSON.readTree(Files.readString(this.manifestPath, StandardCharsets.UTF_8));
        validateManifest(manifest);
        Path commonPath = resolveArtifact(manifest.path("shared_source").asText());
        this.commonSource = readRubySource(commonPath, "shared Graal source");
        this.routes = parseRoutes(manifest);
        this.units = parseUnits(manifest, granularity);
      } catch (IOException error) {
        throw new IllegalArgumentException("cannot read generated Graal manifest: " + this.manifestPath, error);
      }
      if (routes.isEmpty()) throw new IllegalArgumentException("Graal manifest contains no routes");
      if (units.isEmpty()) throw new IllegalArgumentException("Graal manifest contains no " + granularity + " isolate units");
    }

    static Settings fromEnv() {
      Path appRoot = Path.of(env("APP_ROOT", "../ores-ror.rb")).toAbsolutePath().normalize();
      Path manifest = Path.of(env("GRAAL_MANIFEST_PATH", appRoot.resolve("generated/graal/manifest.json").toString()));
      return new Settings(
        env("BIND_HOST", "127.0.0.1"),
        integer("PORT", 8080, 1, 65535),
        appRoot,
        manifest,
        env("ISOLATION_GRANULARITY", "route").toLowerCase(),
        env("DATA_API_URL", "http://127.0.0.1:8787/v1"),
        env("DATA_API_TOKEN", ""),
        integerAlias("CONTEXT_MAX_CONCURRENCY", "ISOLATE_MAX_CONCURRENCY", 5, 1, 5),
        1000L * integerAlias("CONTEXT_MAX_AGE_SECONDS", "ISOLATE_MAX_AGE_SECONDS", 1800, 60, 1800),
        1000L * integerAlias("CONTEXT_IDLE_SECONDS", "ISOLATE_IDLE_SECONDS", 300, 30, 300),
        1000L * integerAlias("CONTEXT_DRAIN_SECONDS", "ISOLATE_DRAIN_SECONDS", 30, 1, 300),
        1000L * integer("REQUEST_TIMEOUT_SECONDS", 15, 1, 300));
    }

    static Settings test(Path appRoot, String granularity, int workers) {
      Path root = appRoot.toAbsolutePath().normalize();
      return new Settings(
        "127.0.0.1",
        0,
        root,
        root.resolve("generated/graal/manifest.json"),
        granularity,
        "http://127.0.0.1:9/v1",
        "",
        workers,
        1_800_000,
        300_000,
        5_000,
        15_000);
    }

    int unitCount() {
      return units.size();
    }

    RouteDef resolveRoute(String method, String path) {
      for (RouteDef route : routes) {
        if (route.matches(method, path)) return route;
      }
      return null;
    }

    String unitKey(RouteDef route) {
      return granularity.equals("route") ? "route:" + route.routeId : "group:" + route.group;
    }

    UnitDef unitFor(RouteDef route) {
      UnitDef unit = units.get(unitKey(route));
      if (unit == null) throw new IllegalStateException("missing isolate unit " + unitKey(route));
      return unit;
    }

    void validateManifest(JsonNode manifest) {
      if (manifest.path("rails_boot").asBoolean(true) || manifest.path("rails_application_initialized").asBoolean(true)) {
        throw new IllegalArgumentException("Graal manifest must be Rails-free");
      }
      if (!"one-long-lived-context-per-route-or-group-isolate".equals(manifest.path("context_model").asText())) {
        throw new IllegalArgumentException("unexpected Graal context model");
      }
      if (manifest.path("max_concurrency_per_isolate").asInt(0) > 5) {
        throw new IllegalArgumentException("manifest exceeds five threads per isolate");
      }
      if (!"process-shared".equals(manifest.path("engine_scope").asText())) {
        throw new IllegalArgumentException("Graal manifest must use a process-shared Engine");
      }
    }

    List<RouteDef> parseRoutes(JsonNode manifest) {
      List<RouteDef> parsed = new ArrayList<>();
      for (JsonNode route : manifest.path("routes")) {
        parsed.add(new RouteDef(
          route.path("verb").asText(),
          route.path("path").asText(),
          route.path("route_id").asText(),
          route.path("group").asText()));
      }
      return List.copyOf(parsed);
    }

    Map<String, UnitDef> parseUnits(JsonNode manifest, String selectedKind) {
      Map<String, UnitDef> parsed = new LinkedHashMap<>();
      for (JsonNode unit : manifest.path("isolate_units")) {
        String kind = unit.path("kind").asText();
        if (!selectedKind.equals(kind)) continue;
        String key = unit.path("key").asText();
        String group = unit.path("group").asText();
        List<String> ids = new ArrayList<>();
        unit.path("route_ids").forEach(id -> ids.add(id.asText()));
        JsonNode sources = unit.path("sources");
        if (!sources.isArray() || sources.size() != 2) {
          throw new IllegalArgumentException("isolate unit must contain common + unit source: " + key);
        }
        Path unitPath = resolveArtifact(sources.get(1).asText());
        String source = readRubySource(unitPath, "Graal isolate unit " + key);
        parsed.put(key, new UnitDef(kind, key, group, ids, unitPath, source));
      }
      return Map.copyOf(parsed);
    }

    Path resolveArtifact(String manifestPath) {
      Path resolved = appRoot.resolve(manifestPath).normalize();
      if (!resolved.startsWith(appRoot)) throw new IllegalArgumentException("artifact escapes APP_ROOT: " + manifestPath);
      if (!Files.isRegularFile(resolved)) throw new IllegalArgumentException("missing generated artifact: " + resolved);
      return resolved;
    }

    static String readRubySource(Path path, String label) {
      try {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        if (source.contains("config/environment") || source.contains("Rails.application")) {
          throw new IllegalArgumentException(label + " must not boot Rails");
        }
        if (source.contains("require_relative")) {
          throw new IllegalArgumentException(label + " must be filesystem-independent");
        }
        return source;
      } catch (IOException error) {
        throw new IllegalArgumentException("cannot read " + label + ": " + path, error);
      }
    }

    static int integer(String name, int defaultValue, int min, int max) {
      int value = Integer.parseInt(env(name, Integer.toString(defaultValue)));
      if (value < min || value > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
      return value;
    }

    static int integerAlias(String preferred, String legacy, int defaultValue, int min, int max) {
      String value = System.getenv(preferred);
      if (value == null || value.isBlank()) value = System.getenv(legacy);
      if (value == null || value.isBlank()) return defaultValue;
      int parsed = Integer.parseInt(value);
      if (parsed < min || parsed > max) throw new IllegalArgumentException(preferred + " out of range");
      return parsed;
    }

    static String env(String name, String defaultValue) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? defaultValue : value;
    }
  }

  static final class Cluster implements AutoCloseable {
    final Settings settings;
    final HttpBridge http;
    final Engine engine;
    final Source commonSource;
    final Map<String, Source> unitSources = new HashMap<>();
    final Map<String, GraalWorker> cells = new LinkedHashMap<>();
    final List<GraalWorker> draining = new ArrayList<>();
    final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(named("maintenance"));
    long nextId;
    boolean closed;

    Cluster(Settings settings) throws Exception {
      this.settings = settings;
      this.http = new HttpBridge(settings.dataUrl, settings.dataToken);
      this.engine = Engine.newBuilder("ruby").build();
      this.commonSource = source(settings.commonSource, "generated/graal/common.rb");
      for (UnitDef unit : settings.units.values()) {
        unitSources.put(unit.key, source(unit.unitSource, unit.unitPath.getFileName().toString() + "-" + safeName(unit.key)));
      }
      try {
        for (UnitDef unit : settings.units.values()) cells.put(unit.key, create(unit));
      } catch (Throwable error) {
        close();
        throw error;
      }
      maintenance.scheduleAtFixedRate(this::maintainSafe, 1, 1, TimeUnit.SECONDS);
    }

    static Source source(String code, String name) throws IOException {
      return Source.newBuilder("ruby", code, name).interactive(true).cached(true).build();
    }

    JsonNode invoke(ObjectNode request) throws Exception {
      RouteDef route = settings.resolveRoute(request.path("method").asText("GET"), request.path("path").asText("/"));
      if (route == null) {
        ObjectNode missing = JSON.createObjectNode();
        missing.put("status", 404);
        missing.set("headers", JSON.createObjectNode().put("content-type", "application/json; charset=utf-8"));
        missing.put("body", "{\"error\":\"route not found\"}");
        return missing;
      }

      GraalWorker cell;
      CompletableFuture<JsonNode> future;
      synchronized (this) {
        maintain(System.currentTimeMillis());
        UnitDef unit = settings.unitFor(route);
        cell = cells.get(unit.key);
        if (cell == null || !cell.accepting()) {
          cell = create(unit);
          cells.put(unit.key, cell);
        }
        future = cell.submit(request);
      }

      try {
        return future.get(settings.requestTimeoutMs, TimeUnit.MILLISECONDS);
      } catch (TimeoutException timeout) {
        synchronized (this) {
          hardReplace(cell, "request timeout");
        }
        throw timeout;
      }
    }

    synchronized int contextCount() {
      return (int) cells.values().stream().filter(cell -> !cell.closed()).count();
    }

    synchronized int workerCount() {
      return cells.values().stream().mapToInt(cell -> cell.settings.workersPerIsolate).sum();
    }

    synchronized int unitCount() {
      return cells.size();
    }

    synchronized String contextIdForUnit(String key) {
      GraalWorker cell = cells.get(key);
      return cell == null ? "" : cell.contextId;
    }

    GraalWorker create(UnitDef unit) throws Exception {
      Source unitSource = unitSources.get(unit.key);
      if (unitSource == null) throw new IllegalStateException("missing cached Source for " + unit.key);
      return new GraalWorker("isolate-" + (++nextId), unit, settings, engine, commonSource, unitSource, http);
    }

    void hardReplace(GraalWorker cell, String reason) throws Exception {
      if (closed || cells.get(cell.unit.key) != cell) return;
      GraalWorker replacement = create(cell.unit);
      cells.put(cell.unit.key, replacement);
      cell.hardCancel(reason);
    }

    void gracefulReplace(GraalWorker cell, String reason) throws Exception {
      if (closed || cells.get(cell.unit.key) != cell) return;
      GraalWorker replacement = create(cell.unit);
      cells.put(cell.unit.key, replacement);
      cell.retire(reason);
      draining.add(cell);
    }

    void maintainSafe() {
      try {
        synchronized (this) {
          if (!closed) maintain(System.currentTimeMillis());
        }
      } catch (Throwable error) {
        System.err.println(safe(error));
      }
    }

    void maintain(long now) throws Exception {
      for (GraalWorker cell : new ArrayList<>(cells.values())) {
        if (!cell.accepting()) continue;
        if (cell.old(now)) {
          gracefulReplace(cell, "max age");
        } else if (cell.idle(now)) {
          cells.remove(cell.unit.key);
          cell.closeAfterDrain();
        }
      }

      List<GraalWorker> gone = new ArrayList<>();
      for (GraalWorker cell : draining) {
        if (cell.closed()) {
          gone.add(cell);
        } else if (cell.load() == 0) {
          cell.close();
          gone.add(cell);
        } else if (now - cell.retiredAt.get() >= settings.drainMs) {
          cell.hardCancel("drain timeout");
          gone.add(cell);
        }
      }
      draining.removeAll(gone);
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      closed = true;
      maintenance.shutdownNow();
      for (GraalWorker cell : cells.values()) cell.closeAfterDrain();
      for (GraalWorker cell : draining) cell.closeAfterDrain();
      cells.clear();
      draining.clear();
      engine.close();
    }
  }

  static final class GraalWorker implements AutoCloseable {
    final String id;
    final String contextId;
    final UnitDef unit;
    final Settings settings;
    final Context context;
    final Value invoke;
    final ThreadPoolExecutor pool;
    final long born = System.currentTimeMillis();
    final AtomicLong last = new AtomicLong(born);
    final AtomicLong retiredAt = new AtomicLong(Long.MAX_VALUE);
    final AtomicInteger active = new AtomicInteger();
    volatile boolean open = true;
    volatile boolean closed;

    GraalWorker(String id, UnitDef unit, Settings settings, Engine engine, Source commonSource, Source unitSource, HttpBridge http) throws Exception {
      this.id = id;
      this.contextId = id + "-context";
      this.unit = unit;
      this.settings = settings;
      this.context = newContext(engine);
      try {
        context.eval(commonSource);
        Value factory = context.eval(unitSource);
        if (!factory.canExecute()) throw new IllegalStateException("Graal isolate unit did not return an executable factory: " + unit.key);
        this.invoke = factory.execute((ProxyExecutable) http::call);
        if (!invoke.canExecute()) throw new IllegalStateException("Graal isolate unit factory did not return an executable invoker: " + unit.key);
      } catch (Throwable error) {
        context.close(true);
        throw error;
      }

      this.pool = new ThreadPoolExecutor(
        settings.workersPerIsolate,
        settings.workersPerIsolate,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(settings.workersPerIsolate * 8),
        named(safeName(unit.key) + "-worker"),
        new ThreadPoolExecutor.AbortPolicy());
    }

    static Context newContext(Engine engine) {
      IOAccess io = IOAccess.newBuilder().allowHostFileAccess(false).allowHostSocketAccess(false).build();
      return Context.newBuilder("ruby")
        .engine(engine)
        .allowExperimentalOptions(true)
        .allowAllAccess(false)
        .allowHostAccess(HostAccess.EXPLICIT)
        .allowHostClassLookup(name -> false)
        .allowHostClassLoading(false)
        .allowNativeAccess(true)
        .allowCreateProcess(false)
        .allowCreateThread(false)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .allowIO(io)
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .build();
    }

    CompletableFuture<JsonNode> submit(ObjectNode request) {
      if (!accepting()) throw new RejectedExecutionException("isolate retiring");
      last.set(System.currentTimeMillis());
      CompletableFuture<JsonNode> future = new CompletableFuture<>();
      pool.execute(() -> {
        active.incrementAndGet();
        try {
          Value value = invoke.execute(JSON.writeValueAsString(request));
          JsonNode response = JSON.readTree(value.asString());
          future.complete(withDiagnostics(response));
        } catch (Throwable error) {
          future.completeExceptionally(error);
        } finally {
          active.decrementAndGet();
          last.set(System.currentTimeMillis());
        }
      });
      return future;
    }

    JsonNode withDiagnostics(JsonNode response) {
      if (!(response instanceof ObjectNode object)) return response;
      JsonNode existing = object.get("headers");
      ObjectNode headers;
      if (existing instanceof ObjectNode existingObject) headers = existingObject;
      else {
        headers = JSON.createObjectNode();
        object.set("headers", headers);
      }
      headers.put("x-ores-graal-context-id", contextId);
      headers.put("x-ores-graal-isolate-key", unit.key);
      headers.put("x-ores-graal-worker-thread", Thread.currentThread().getName());
      return object;
    }

    int load() {
      return active.get() + pool.getQueue().size();
    }

    boolean accepting() {
      return open && !closed;
    }

    boolean closed() {
      return closed;
    }

    boolean old(long now) {
      return now - born >= settings.maxAgeMs;
    }

    boolean idle(long now) {
      return load() == 0 && now - last.get() >= settings.idleMs;
    }

    void retire(String reason) {
      if (!open || closed) return;
      open = false;
      retiredAt.set(System.currentTimeMillis());
      pool.shutdown();
    }

    synchronized void hardCancel(String reason) {
      if (closed) return;
      open = false;
      pool.shutdownNow();
      try {
        context.close(true);
      } catch (Throwable error) {
        System.err.println("hard-cancel " + unit.key + " (" + reason + "): " + safe(error));
      }
      closed = true;
    }

    void closeAfterDrain() {
      if (closed) return;
      open = false;
      pool.shutdown();
      try {
        if (!pool.awaitTermination(settings.drainMs, TimeUnit.MILLISECONDS)) {
          hardCancel("drain timeout");
          return;
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        hardCancel("drain interrupted");
        return;
      }
      closeResources();
    }

    @Override
    public void close() {
      if (closed) return;
      open = false;
      pool.shutdown();
      if (load() != 0) throw new IllegalStateException("isolate busy: " + unit.key);
      closeResources();
    }

    synchronized void closeResources() {
      if (closed) return;
      context.close();
      closed = true;
    }
  }

  static final class HttpBridge {
    final URI base;
    final String token;
    final HttpClient client;

    HttpBridge(String url, String token) {
      this.base = URI.create(url.replaceAll("/$", "") + "/");
      this.token = token;
      this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    }

    Object call(Value... args) {
      try {
        JsonNode request = JSON.readTree(args[0].asString());
        String method = request.path("method").asText("GET").toUpperCase();
        String path = request.path("path").asText();
        if (!path.startsWith("/") || path.contains("://")) throw new IllegalArgumentException("relative HTTP path required");
        String query = query(request.path("query"));
        URI uri = URI.create(base.toString().replaceAll("/$", "") + path + (query.isEmpty() ? "" : "?" + query));
        if (!sameOrigin(base, uri)) throw new IllegalArgumentException("HTTP origin escape");

        JsonNode bodyNode = request.get("body");
        String body = bodyNode == null || bodyNode.isNull() ? "" : JSON.writeValueAsString(bodyNode);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(10))
          .header("accept", "application/json")
          .header("content-type", "application/json");
        if (token != null && !token.isEmpty()) builder.header("authorization", "Bearer " + token);
        builder.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (InputStream input = response.body()) {
          bytes = input.readNBytes(MAX_BODY + 1);
        }
        if (bytes.length > MAX_BODY) throw new IllegalStateException("HTTP response too large");
        return JSON.writeValueAsString(
          JSON.createObjectNode().put("ok", true).put("status", response.statusCode()).put("body", new String(bytes, StandardCharsets.UTF_8)));
      } catch (Exception error) {
        try {
          return JSON.writeValueAsString(JSON.createObjectNode().put("ok", false).put("error", safe(error)));
        } catch (Exception ignored) {
          return "{\"ok\":false,\"error\":\"bridge failure\"}";
        }
      }
    }

    static String query(JsonNode query) {
      if (query == null || !query.isObject() || query.isEmpty()) return "";
      List<String> pairs = new ArrayList<>();
      query.fields().forEachRemaining(entry -> pairs.add(
        URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue().asText(), StandardCharsets.UTF_8)));
      return String.join("&", pairs);
    }

    static boolean sameOrigin(URI first, URI second) {
      return first.getScheme().equalsIgnoreCase(second.getScheme())
        && first.getHost().equalsIgnoreCase(second.getHost())
        && effectivePort(first) == effectivePort(second);
    }

    static int effectivePort(URI uri) {
      if (uri.getPort() >= 0) return uri.getPort();
      return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
  }

  static String requestId(String value) {
    return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : "ores-request-" + UUID.randomUUID();
  }

  static ThreadFactory named(String prefix) {
    AtomicInteger id = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + "-" + id.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  static String safeName(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]+", "-");
  }

  static String safe(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) current = current.getCause();
    String message = current.getMessage();
    return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
