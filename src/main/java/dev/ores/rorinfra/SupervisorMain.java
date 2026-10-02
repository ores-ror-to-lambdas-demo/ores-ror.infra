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
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
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
  static final int MAX_GUEST_RESPONSE = 2 * 1024 * 1024;
  static final int MAX_MANIFEST_BYTES = 1024 * 1024;
  static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;
  static final int MAX_ROUTES = 1024;
  static final int MAX_UNITS = 2048;
  static final int MAX_ROUTE_PATH = 2048;
  static final int MAX_IDENTIFIER = 256;
  static final int MAX_BRIDGE_URL = 16 * 1024;
  static final int MAX_RESPONSE_HEADERS = 64;
  static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
  static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade"
  );
  static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");

  private SupervisorMain() {}

  public static void main(String[] args) throws Exception {
    Settings settings = Settings.fromEnv();
    try (Cluster cluster = new Cluster(settings)) {
      int ingressThreads = Math.max(8, Math.min(64, settings.unitCount() * Math.min(settings.workersPerIsolate, 2)));
      ThreadPoolExecutor ingress = new ThreadPoolExecutor(
        ingressThreads,
        ingressThreads,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(Math.min(1024, Math.max(128, ingressThreads * 8))),
        named("ingress"),
        new ThreadPoolExecutor.CallerRunsPolicy());
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
        "TruffleRuby/Graal listening on http://%s:%d worker-placement=%s startup=%s selected-workers=%d contexts/worker=1 admission-limit/context=%d shared-engine=1%n",
        settings.bindHost,
        settings.port,
        settings.placement.defaultStrategy,
        settings.placement.startup,
        settings.unitCount(),
        settings.workersPerIsolate);
      Thread.currentThread().join();
    }
  }

  static void handle(HttpExchange exchange, Cluster cluster) throws IOException {
    try {
      String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
      if (!HTTP_METHODS.contains(method)) {
        sendError(exchange, 405, "unsupported HTTP method");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      String rawQuery = exchange.getRequestURI().getRawQuery();
      if (path == null || path.length() > MAX_ROUTE_PATH || path.indexOf('\0') >= 0
          || (rawQuery != null && rawQuery.length() > MAX_BRIDGE_URL)) {
        sendError(exchange, 414, "request target too large or invalid");
        return;
      }

      byte[] input = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
      if (input.length > MAX_BODY) {
        sendError(exchange, 413, "request body too large");
        return;
      }

      ObjectNode request = JSON.createObjectNode();
      request.put("request_id", requestId(exchange.getRequestHeaders().getFirst("x-request-id")));
      request.put("method", method);
      request.put("path", path);
      request.put("query_string", rawQuery == null ? "" : rawQuery);
      request.put("body", decodeUtf8Strict(input));
      ObjectNode headers = request.putObject("headers");
      String contentType = exchange.getRequestHeaders().getFirst("content-type");
      if (contentType != null) {
        if (contentType.length() > 8192) {
          sendError(exchange, 431, "content-type header too large");
          return;
        }
        headers.put("content-type", contentType);
      }
      String accept = exchange.getRequestHeaders().getFirst("accept");
      if (accept != null) {
        if (accept.length() > 8192) {
          sendError(exchange, 431, "accept header too large");
          return;
        }
        headers.put("accept", accept);
      }

      JsonNode result = cluster.invoke(request);
      int status = result.path("status").asInt(500);
      if (status < 100 || status > 599) status = 500;
      result.path("headers").fields().forEachRemaining(header -> {
        String name = header.getKey().toLowerCase(Locale.ROOT);
        String value = header.getValue().isTextual() ? header.getValue().asText() : null;
        if (safeResponseHeader(name, value)) {
          exchange.getResponseHeaders().set(name, value);
        }
      });
      byte[] body = result.path("body").asText("").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    } catch (CharacterCodingException error) {
      sendError(exchange, 400, "request body must be valid UTF-8");
    } catch (RejectedExecutionException error) {
      sendError(exchange, 503, "Graal isolate saturated or retiring");
    } catch (TimeoutException error) {
      sendError(exchange, 504, "Graal request timed out; isolate replaced");
    } catch (Exception error) {
      System.err.println("Graal request failed: " + safe(error));
      sendError(exchange, 500, "internal Graal execution failure");
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

  static boolean safeResponseHeader(String name, String value) {
    return name != null
      && HEADER_NAME.matcher(name).matches()
      && !"content-length".equals(name)
      && !HOP_BY_HOP_HEADERS.contains(name)
      && value != null
      && value.indexOf('\r') < 0
      && value.indexOf('\n') < 0
      && value.length() <= 8192;
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

  static final class WorkerPlacement {
    static final String SCHEMA = "ores-graal-worker-placement/v1";

    final String defaultStrategy;
    final String startup;
    final Map<String, String> routeAssignments;

    WorkerPlacement(String defaultStrategy, String startup, Map<String, String> routeAssignments) {
      this.defaultStrategy = defaultStrategy;
      this.startup = startup;
      this.routeAssignments = Map.copyOf(routeAssignments);
      if (!List.of("route", "group").contains(defaultStrategy)) {
        throw new IllegalArgumentException("worker placement default_strategy must be route or group");
      }
      if (!List.of("lazy", "eager").contains(startup)) {
        throw new IllegalArgumentException("worker placement startup must be lazy or eager");
      }
    }

    static WorkerPlacement fromEnv(Path appRoot) {
      String configured = env("GRAAL_WORKER_PLACEMENT_FILE", "").trim();
      if (configured.isEmpty()) {
        String legacy = env("GRAAL_WORKER_PLACEMENT", env("ISOLATION_GRANULARITY", "route")).toLowerCase(Locale.ROOT);
        String startup = env("GRAAL_WORKER_STARTUP", "lazy").toLowerCase(Locale.ROOT);
        return new WorkerPlacement(legacy, startup, Map.of());
      }

      Path path = Path.of(configured);
      if (!path.isAbsolute()) path = appRoot.resolve(path);
      path = path.toAbsolutePath().normalize();
      try {
        Path realAppRoot = appRoot.toRealPath();
        Path real = path.toRealPath();
        if (!real.startsWith(realAppRoot)) {
          throw new IllegalArgumentException("GRAAL_WORKER_PLACEMENT_FILE must remain inside APP_ROOT");
        }
        if (Files.size(real) > 256 * 1024) {
          throw new IllegalArgumentException("worker placement file exceeds 256 KiB");
        }
        JsonNode root = JSON.readTree(Files.readString(real, StandardCharsets.UTF_8));
        if (!SCHEMA.equals(root.path("schema").asText())) {
          throw new IllegalArgumentException("unsupported worker placement schema");
        }
        String defaultStrategy = root.path("default_strategy").asText("route").toLowerCase(Locale.ROOT);
        String startup = root.path("startup").asText("lazy").toLowerCase(Locale.ROOT);
        Map<String, String> assignments = new LinkedHashMap<>();
        JsonNode configuredAssignments = root.path("route_assignments");
        if (!configuredAssignments.isObject()) {
          throw new IllegalArgumentException("worker placement route_assignments must be an object");
        }
        configuredAssignments.fields().forEachRemaining(entry -> {
          String routeId = entry.getKey();
          String unitKey = entry.getValue().asText();
          if (routeId.isBlank() || routeId.length() > MAX_IDENTIFIER || unitKey.isBlank() || unitKey.length() > MAX_IDENTIFIER + 32) {
            throw new IllegalArgumentException("worker placement contains an invalid route/unit key");
          }
          assignments.put(routeId, unitKey);
        });
        return new WorkerPlacement(defaultStrategy, startup, assignments);
      } catch (IOException error) {
        throw new IllegalArgumentException("cannot read GRAAL_WORKER_PLACEMENT_FILE: " + path, error);
      }
    }

    String unitKey(RouteDef route) {
      String assigned = routeAssignments.get(route.routeId);
      if (assigned != null) return assigned;
      return "group".equals(defaultStrategy) ? "group:" + route.group : "route:" + route.routeId;
    }

    boolean eager() {
      return "eager".equals(startup);
    }
  }

  static final class Settings {
    final String bindHost;
    final int port;
    final Path appRoot;
    final Path manifestPath;
    final WorkerPlacement placement;
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
    final Path artifactRoot;
    final boolean exposeDiagnostics;

    Settings(
      String bindHost,
      int port,
      Path appRoot,
      Path manifestPath,
      WorkerPlacement placement,
      String dataUrl,
      String dataToken,
      int workersPerIsolate,
      long maxAgeMs,
      long idleMs,
      long drainMs,
      long requestTimeoutMs,
      boolean exposeDiagnostics
    ) {
      this.bindHost = bindHost;
      this.port = port;
      this.appRoot = appRoot.toAbsolutePath().normalize();
      this.manifestPath = manifestPath.toAbsolutePath().normalize();
      this.placement = placement;
      this.dataUrl = dataUrl;
      this.dataToken = dataToken;
      this.workersPerIsolate = workersPerIsolate;
      this.maxAgeMs = maxAgeMs;
      this.idleMs = idleMs;
      this.drainMs = drainMs;
      this.requestTimeoutMs = requestTimeoutMs;
      this.exposeDiagnostics = exposeDiagnostics;
      Objects.requireNonNull(placement, "placement");
      URI uri = URI.create(dataUrl);
      if (!List.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        throw new IllegalArgumentException("DATA_API_URL must be an http(s) origin/base path without credentials, query, or fragment");
      }
      if (dataUrl.length() > 4096) {
        throw new IllegalArgumentException("DATA_API_URL is too long");
      }
      if (dataToken.length() > 8192 || dataToken.indexOf('\r') >= 0 || dataToken.indexOf('\n') >= 0) {
        throw new IllegalArgumentException("DATA_API_TOKEN is too long or contains invalid control characters");
      }
      if (!dataToken.isEmpty() && "http".equalsIgnoreCase(uri.getScheme()) && !isLoopbackHost(uri.getHost())) {
        throw new IllegalArgumentException("DATA_API_TOKEN requires HTTPS unless DATA_API_URL is loopback");
      }

      try {
        Path realAppRoot = this.appRoot.toRealPath();
        Path realManifest = this.manifestPath.toRealPath();
        if (!realManifest.startsWith(realAppRoot)) {
          throw new IllegalArgumentException("GRAAL_MANIFEST_PATH must remain inside APP_ROOT");
        }
        this.artifactRoot = realManifest.getParent();
        if (Files.size(realManifest) > MAX_MANIFEST_BYTES) {
          throw new IllegalArgumentException("generated Graal manifest exceeds size limit");
        }
        JsonNode manifest = JSON.readTree(Files.readString(realManifest, StandardCharsets.UTF_8));
        validateManifest(manifest);
        Path commonPath = resolveArtifact(manifest.path("shared_source").asText());
        this.commonSource = readRubySource(commonPath, "shared Graal source");
        this.routes = parseRoutes(manifest);
        this.units = parseUnits(manifest);
        validatePlacement();
      } catch (IOException error) {
        throw new IllegalArgumentException("cannot read generated Graal manifest: " + this.manifestPath, error);
      }
      if (routes.isEmpty()) throw new IllegalArgumentException("Graal manifest contains no routes");
      if (units.isEmpty()) throw new IllegalArgumentException("Graal manifest contains no isolate units");
    }

    static Settings fromEnv() {
      Path appRoot = Path.of(env("APP_ROOT", "../ores-ror.rb")).toAbsolutePath().normalize();
      Path manifest = Path.of(env("GRAAL_MANIFEST_PATH", appRoot.resolve("generated/graal/manifest.json").toString()));
      return new Settings(
        env("BIND_HOST", "127.0.0.1"),
        integer("PORT", 8080, 1, 65535),
        appRoot,
        manifest,
        WorkerPlacement.fromEnv(appRoot),
        env("DATA_API_URL", "http://127.0.0.1:8787/v1"),
        env("DATA_API_TOKEN", ""),
        integerAliases("CONTEXT_THREAD_POOL_SIZE", List.of("CONTEXT_ADMISSION_LIMIT", "CONTEXT_MAX_CONCURRENCY", "ISOLATE_MAX_CONCURRENCY"), 5, 1, 5),
        1000L * integerAlias("CONTEXT_MAX_AGE_SECONDS", "ISOLATE_MAX_AGE_SECONDS", 1800, 60, 1800),
        1000L * integerAlias("CONTEXT_IDLE_SECONDS", "ISOLATE_IDLE_SECONDS", 300, 30, 300),
        1000L * integerAlias("CONTEXT_DRAIN_SECONDS", "ISOLATE_DRAIN_SECONDS", 30, 1, 300),
        1000L * integer("REQUEST_TIMEOUT_SECONDS", 15, 1, 300),
        bool("EXPOSE_GRAAL_DIAGNOSTICS", false));
    }

    static Settings test(Path appRoot, String granularity, int workers) {
      Path root = appRoot.toAbsolutePath().normalize();
      return new Settings(
        "127.0.0.1",
        0,
        root,
        root.resolve("generated/graal/manifest.json"),
        new WorkerPlacement(granularity, "lazy", Map.of()),
        "http://127.0.0.1:9/v1",
        "",
        workers,
        1_800_000,
        300_000,
        5_000,
        120_000,
        true);
    }

    int unitCount() {
      return selectedUnitKeys().size();
    }

    Set<String> selectedUnitKeys() {
      Set<String> selected = new java.util.LinkedHashSet<>();
      for (RouteDef route : routes) selected.add(placement.unitKey(route));
      return Set.copyOf(selected);
    }

    RouteDef resolveRoute(String method, String path) {
      for (RouteDef route : routes) {
        if (route.matches(method, path)) return route;
      }
      return null;
    }

    String unitKey(RouteDef route) {
      return placement.unitKey(route);
    }

    UnitDef unitFor(RouteDef route) {
      String key = unitKey(route);
      UnitDef unit = units.get(key);
      if (unit == null) throw new IllegalStateException("missing isolate unit " + key);
      if (!unit.routeIds.contains(route.routeId)) {
        throw new IllegalStateException("worker unit " + key + " does not contain route " + route.routeId);
      }
      return unit;
    }

    void validatePlacement() {
      Set<String> knownRoutes = new java.util.HashSet<>();
      for (RouteDef route : routes) knownRoutes.add(route.routeId);
      for (String configuredRoute : placement.routeAssignments.keySet()) {
        if (!knownRoutes.contains(configuredRoute)) {
          throw new IllegalArgumentException("worker placement references unknown route_id " + configuredRoute);
        }
      }
      for (RouteDef route : routes) {
        String key = placement.unitKey(route);
        UnitDef unit = units.get(key);
        if (unit == null) {
          throw new IllegalArgumentException("worker placement selects missing generated unit " + key + " for " + route.routeId);
        }
        if (!unit.routeIds.contains(route.routeId)) {
          throw new IllegalArgumentException("worker placement selects unit " + key + " that does not contain " + route.routeId);
        }
      }
    }

    void validateManifest(JsonNode manifest) {
      if (!"ores-graal-ruby-manifest/v4".equals(manifest.path("schema").asText())) {
        throw new IllegalArgumentException("unsupported Graal manifest schema");
      }
      if (!"ores-ror.rb".equals(manifest.path("application").asText())
          || !"truffleruby".equals(manifest.path("runtime").asText())
          || !"generated/graal/common.rb".equals(manifest.path("shared_source").asText())) {
        throw new IllegalArgumentException("unexpected Graal application/runtime/source identity");
      }
      if (manifest.path("rails_boot").asBoolean(true) || manifest.path("rails_application_initialized").asBoolean(true)) {
        throw new IllegalArgumentException("Graal manifest must be Rails-free");
      }
      if (!"one-long-lived-context-per-route-or-group-isolate".equals(manifest.path("context_model").asText())) {
        throw new IllegalArgumentException("unexpected Graal context model");
      }
      if (manifest.path("contexts_per_isolate").asInt(0) != 1) {
        throw new IllegalArgumentException("Graal manifest must declare exactly one Context per isolate");
      }
      if (manifest.path("host_thread_pool_size_per_context").asInt(0) != 5
          || manifest.path("guest_owner_threads_per_context").asInt(0) != 5
          || manifest.path("execution_concurrency_per_context").asInt(0) != 5) {
        throw new IllegalArgumentException("each Graal Context must declare a five-thread host executor");
      }
      int admissionLimit = manifest.path("max_admitted_in_flight_per_isolate").asInt(0);
      if (admissionLimit < 1 || admissionLimit > 5) {
        throw new IllegalArgumentException("manifest admission limit must be between 1 and 5");
      }
      if (workersPerIsolate > admissionLimit) {
        throw new IllegalArgumentException("configured admission limit exceeds generated manifest limit");
      }
      if (!manifest.path("request_multiplexing").asBoolean(false)) {
        throw new IllegalArgumentException("Graal manifest must explicitly enable request multiplexing");
      }
      if (!"process-shared".equals(manifest.path("engine_scope").asText())) {
        throw new IllegalArgumentException("Graal manifest must use a process-shared Engine");
      }
      if (!"explicit-per-invocation".equals(manifest.path("request_state").asText())
          || manifest.path("thread_identity_is_request_identity").asBoolean(true)
          || manifest.path("guest_filesystem").asBoolean(true)
          || manifest.path("guest_created_threads").asBoolean(true)
          || manifest.path("child_processes").asBoolean(true)
          || manifest.path("native_ffi").asBoolean(true)
          || manifest.path("raw_sockets").asBoolean(true)
          || !"host-http".equals(manifest.path("database_transport").asText())
          || !"ores_gs_http".equals(manifest.path("host_http_binding").asText())) {
        throw new IllegalArgumentException("Graal manifest violates required capability/request-state contract");
      }
    }

    List<RouteDef> parseRoutes(JsonNode manifest) {
      JsonNode routeNodes = manifest.path("routes");
      if (!routeNodes.isArray() || routeNodes.isEmpty() || routeNodes.size() > MAX_ROUTES) {
        throw new IllegalArgumentException("Graal manifest route count is invalid");
      }
      List<RouteDef> parsed = new ArrayList<>();
      Set<String> identities = new java.util.HashSet<>();
      Set<String> routeIds = new java.util.HashSet<>();
      for (JsonNode route : routeNodes) {
        String verb = route.path("verb").asText().toUpperCase(Locale.ROOT);
        String path = route.path("path").asText();
        String routeId = route.path("route_id").asText();
        String group = route.path("group").asText();
        if (!HTTP_METHODS.contains(verb) || !path.startsWith("/") || path.length() > MAX_ROUTE_PATH
            || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0 || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
            || routeId.isBlank() || routeId.length() > MAX_IDENTIFIER
            || group.isBlank() || group.length() > MAX_IDENTIFIER) {
          throw new IllegalArgumentException("invalid route entry in Graal manifest");
        }
        String identity = verb + " " + path;
        if (!identities.add(identity)) {
          throw new IllegalArgumentException("duplicate route in Graal manifest: " + identity);
        }
        if (!routeIds.add(routeId)) {
          throw new IllegalArgumentException("duplicate route_id in Graal manifest: " + routeId);
        }
        parsed.add(new RouteDef(verb, path, routeId, group));
      }
      return List.copyOf(parsed);
    }

    Map<String, UnitDef> parseUnits(JsonNode manifest) {
      JsonNode unitNodes = manifest.path("isolate_units");
      if (!unitNodes.isArray() || unitNodes.isEmpty() || unitNodes.size() > MAX_UNITS) {
        throw new IllegalArgumentException("Graal manifest isolate-unit count is invalid");
      }
      String sharedSource = manifest.path("shared_source").asText();
      Map<String, UnitDef> parsed = new LinkedHashMap<>();
      for (JsonNode unit : unitNodes) {
        String kind = unit.path("kind").asText();
        if (kind.isBlank()) throw new IllegalArgumentException("isolate unit kind is required");
        String key = unit.path("key").asText();
        String group = unit.path("group").asText();
        List<String> ids = new ArrayList<>();
        unit.path("route_ids").forEach(id -> ids.add(id.asText()));
        JsonNode sources = unit.path("sources");
        if (!sources.isArray() || sources.size() != 2 || !sharedSource.equals(sources.get(0).asText())) {
          throw new IllegalArgumentException("isolate unit must contain the declared common + unit source: " + key);
        }
        if (key.isBlank() || key.length() > MAX_IDENTIFIER + 16
            || group.isBlank() || group.length() > MAX_IDENTIFIER
            || ids.isEmpty() || ids.size() > MAX_ROUTES || parsed.containsKey(key)) {
          throw new IllegalArgumentException("invalid or duplicate isolate unit: " + key);
        }
        if (unit.path("context_count").asInt(0) != 1
            || unit.path("host_thread_pool_size").asInt(0) != 5
            || unit.path("guest_owner_threads").asInt(0) != 5
            || unit.path("execution_concurrency").asInt(0) != 5
            || unit.path("admission_limit").asInt(0) != 5
            || !unit.path("request_multiplexing").asBoolean(false)) {
          throw new IllegalArgumentException("invalid isolate execution contract: " + key);
        }
        Path unitPath = resolveArtifact(sources.get(1).asText());
        String source = readRubySource(unitPath, "Graal isolate unit " + key);
        parsed.put(key, new UnitDef(kind, key, group, ids, unitPath, source));
      }
      return Map.copyOf(parsed);
    }

    void validateUnitCoverage(Map<String, UnitDef> parsed, String selectedKind) {
      Map<String, RouteDef> byId = new HashMap<>();
      Map<String, List<RouteDef>> byGroup = new HashMap<>();
      for (RouteDef route : routes) {
        byId.put(route.routeId, route);
        byGroup.computeIfAbsent(route.group, ignored -> new ArrayList<>()).add(route);
      }

      Set<String> covered = new java.util.HashSet<>();
      for (UnitDef unit : parsed.values()) {
        if ("route".equals(selectedKind)) {
          if (unit.routeIds.size() != 1) throw new IllegalArgumentException("route isolate must contain exactly one route: " + unit.key);
          RouteDef route = byId.get(unit.routeIds.get(0));
          if (route == null || !unit.key.equals("route:" + route.routeId) || !unit.group.equals(route.group)) {
            throw new IllegalArgumentException("route isolate does not match route metadata: " + unit.key);
          }
          if (!covered.add(route.routeId)) throw new IllegalArgumentException("route is covered by multiple isolate units: " + route.routeId);
        } else {
          List<RouteDef> expected = byGroup.get(unit.group);
          if (expected == null || !unit.key.equals("group:" + unit.group)) {
            throw new IllegalArgumentException("group isolate does not match route metadata: " + unit.key);
          }
          Set<String> expectedIds = new java.util.HashSet<>();
          expected.forEach(route -> expectedIds.add(route.routeId));
          Set<String> actualIds = new java.util.HashSet<>(unit.routeIds);
          if (actualIds.size() != unit.routeIds.size() || !actualIds.equals(expectedIds)) {
            throw new IllegalArgumentException("group isolate route_ids do not exactly match group routes: " + unit.key);
          }
          for (String routeId : actualIds) {
            if (!covered.add(routeId)) throw new IllegalArgumentException("route is covered by multiple isolate units: " + routeId);
          }
        }
      }
      if (covered.size() != routes.size()) {
        throw new IllegalArgumentException("selected isolate units do not cover every route exactly once");
      }
    }

    Path resolveArtifact(String manifestPath) {
      Path resolved = appRoot.resolve(manifestPath).normalize();
      if (!resolved.startsWith(appRoot)) throw new IllegalArgumentException("artifact escapes APP_ROOT: " + manifestPath);
      try {
        Path real = resolved.toRealPath();
        if (!real.startsWith(artifactRoot)) {
          throw new IllegalArgumentException("artifact escapes generated Graal root: " + manifestPath);
        }
        if (!Files.isRegularFile(real)) throw new IllegalArgumentException("missing generated artifact: " + real);
        return real;
      } catch (IOException error) {
        throw new IllegalArgumentException("cannot resolve generated artifact: " + resolved, error);
      }
    }

    static String readRubySource(Path path, String label) {
      try {
        if (Files.size(path) > MAX_SOURCE_BYTES) {
          throw new IllegalArgumentException(label + " exceeds generated source size limit");
        }
        String source = Files.readString(path, StandardCharsets.UTF_8);
        if (source.contains("config/environment") || source.contains("Rails.application")
            || source.contains("ActionController") || source.contains("ActionView")) {
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
      return integerAliases(preferred, List.of(legacy), defaultValue, min, max);
    }

    static int integerAliases(String preferred, List<String> legacyNames, int defaultValue, int min, int max) {
      String value = System.getenv(preferred);
      if (value == null || value.isBlank()) {
        for (String legacy : legacyNames) {
          value = System.getenv(legacy);
          if (value != null && !value.isBlank()) break;
        }
      }
      if (value == null || value.isBlank()) return defaultValue;
      int parsed = Integer.parseInt(value);
      if (parsed < min || parsed > max) throw new IllegalArgumentException(preferred + " out of range");
      return parsed;
    }

    static boolean bool(String name, boolean defaultValue) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
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
      if (settings.placement.eager()) {
        for (String key : settings.selectedUnitKeys()) {
          UnitDef unit = settings.units.get(key);
          if (unit == null) throw new IllegalStateException("missing eager worker unit " + key);
          cells.put(key, create(unit));
        }
      }
      // Lazy mode creates a worker only on the first request that selects its
      // configured unit. Eager mode prewarms every distinct selected unit.
      maintenance.scheduleAtFixedRate(this::maintainSafe, 1, 1, TimeUnit.SECONDS);
    }

    static Source source(String code, String name) throws IOException {
      return Source.newBuilder("ruby", code, name).cached(true).build();
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
      return cells.values().size();
    }

    synchronized int admissionCapacity() {
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
      cells.remove(cell.unit.key);
      cell.hardCancel(reason);
      GraalWorker replacement = create(cell.unit);
      cells.put(cell.unit.key, replacement);
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
    record RuntimeState(Context context, Value invoke) {}

    final String id;
    final String contextId;
    final UnitDef unit;
    final Settings settings;
    final ThreadPoolExecutor owner;
    final Semaphore admission;
    final Context context;
    final Value invoke;
    final long born = System.currentTimeMillis();
    final AtomicLong last = new AtomicLong(born);
    final AtomicLong retiredAt = new AtomicLong(Long.MAX_VALUE);
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger guestEntries = new AtomicInteger();
    final AtomicInteger maxGuestEntries = new AtomicInteger();
    volatile boolean open = true;
    volatile boolean closed;

    GraalWorker(
      String id,
      UnitDef unit,
      Settings settings,
      Engine engine,
      Source commonSource,
      Source unitSource,
      HttpBridge http
    ) throws Exception {
      this.id = id;
      this.contextId = id + "-context";
      this.unit = unit;
      this.settings = settings;
      this.admission = new Semaphore(settings.workersPerIsolate, true);
      this.owner = new ThreadPoolExecutor(
        settings.workersPerIsolate,
        settings.workersPerIsolate,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(Math.max(8, settings.workersPerIsolate * 2)),
        named(safeName(unit.key) + "-guest-worker"),
        new ThreadPoolExecutor.AbortPolicy());

      RuntimeState state;
      try {
        state = owner.submit(() -> {
          Context created = newContext(engine);
          try {
            created.eval(commonSource);
            Value factory = created.eval(unitSource);
            if (!factory.canExecute()) {
              throw new IllegalStateException("Graal isolate unit did not return an executable factory: " + unit.key);
            }
            Value bound = factory.execute((ProxyExecutable) http::call);
            if (!bound.canExecute()) {
              throw new IllegalStateException("Graal isolate unit factory did not return an executable invoker: " + unit.key);
            }
            return new RuntimeState(created, bound);
          } catch (Throwable error) {
            try {
              created.close(true);
            } catch (Throwable ignored) {
              // initialization already failed
            }
            throw error;
          }
        }).get();
      } catch (Throwable error) {
        owner.shutdownNow();
        throw error;
      }
      this.context = state.context();
      this.invoke = state.invoke();
    }

    static Context newContext(Engine engine) {
      IOAccess io = IOAccess.newBuilder().allowHostFileAccess(false).allowHostSocketAccess(false).build();
      return Context.newBuilder("ruby")
        .engine(engine)
        .allowExperimentalOptions(true)
        .option("ruby.single-threaded", "false")
        .option("ruby.platform-native", "false")
        .option("ruby.cexts", "false")
        .option("ruby.rubygems", "false")
        .allowAllAccess(false)
        .allowHostAccess(HostAccess.EXPLICIT)
        .allowHostClassLookup(name -> false)
        .allowHostClassLoading(false)
        .allowNativeAccess(false)
        .allowCreateProcess(false)
        .allowCreateThread(false)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .allowIO(io)
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .build();
    }

    CompletableFuture<JsonNode> submit(ObjectNode request) {
      if (!accepting()) throw new RejectedExecutionException("isolate retiring");
      if (!admission.tryAcquire()) throw new RejectedExecutionException("isolate admission limit reached");
      last.set(System.currentTimeMillis());
      active.incrementAndGet();

      CompletableFuture<JsonNode> future = new CompletableFuture<>();
      try {
        owner.execute(() -> {
          int entries = guestEntries.incrementAndGet();
          maxGuestEntries.accumulateAndGet(entries, Math::max);
          try {
            Value value = invoke.execute(JSON.writeValueAsString(request));
            String encoded = value.asString();
            if (encoded.length() > MAX_GUEST_RESPONSE
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_GUEST_RESPONSE) {
              throw new IllegalStateException("guest response exceeds size limit");
            }
            JsonNode response = JSON.readTree(encoded);
            validateGuestResponse(response);
            future.complete(withDiagnostics(response));
          } catch (Throwable error) {
            future.completeExceptionally(error);
          } finally {
            guestEntries.decrementAndGet();
            active.decrementAndGet();
            admission.release();
            last.set(System.currentTimeMillis());
          }
        });
      } catch (RuntimeException error) {
        active.decrementAndGet();
        admission.release();
        throw error;
      }
      return future;
    }

    String evalForTest(String ruby) throws Exception {
      return owner.submit(() -> context.eval("ruby", ruby).asString()).get();
    }

    static void validateGuestResponse(JsonNode response) {
      if (!(response instanceof ObjectNode object)
          || !object.path("headers").isObject()
          || !object.path("body").isTextual()
          || object.path("headers").size() > MAX_RESPONSE_HEADERS) {
        throw new IllegalStateException("guest response envelope is invalid");
      }
    }

    JsonNode withDiagnostics(JsonNode response) {
      if (!settings.exposeDiagnostics || !(response instanceof ObjectNode object)) return response;
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
      return active.get();
    }

    int maxConcurrentGuestEntries() {
      return maxGuestEntries.get();
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
    }

    synchronized void hardCancel(String reason) {
      if (closed) return;
      open = false;
      owner.shutdownNow();
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
      long deadline = System.currentTimeMillis() + settings.drainMs;
      while (load() != 0 && System.currentTimeMillis() < deadline) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      if (load() != 0) {
        hardCancel("drain timeout");
        return;
      }
      close();
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      open = false;
      if (load() != 0) throw new IllegalStateException("isolate busy: " + unit.key);
      try {
        owner.submit(() -> {
          context.close();
          return null;
        }).get();
      } catch (Exception error) {
        throw new IllegalStateException("failed to close isolate " + unit.key, error);
      } finally {
        owner.shutdown();
      }
      closed = true;
    }
  }

  static final class HttpBridge {
    final URI base;
    final String token;
    final HttpClient client;

    HttpBridge(String url, String token) {
      this.base = URI.create(url.replaceAll("/+$", "") + "/");
      this.token = token;
      this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    }

    Object call(Value... args) {
      try {
        if (args.length != 1) throw new IllegalArgumentException("host HTTP bridge expects one request envelope");
        String encodedRequest = args[0].asString();
        if (encodedRequest.length() > MAX_BODY
            || encodedRequest.getBytes(StandardCharsets.UTF_8).length > MAX_BODY) {
          throw new IllegalArgumentException("host HTTP bridge request too large");
        }
        JsonNode request = JSON.readTree(encodedRequest);
        String method = request.path("method").asText("GET").toUpperCase(Locale.ROOT);
        String path = request.path("path").asText();
        String query = query(request.path("query"));
        URI uri = targetUri(base, method, path, query);

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
        String responseBody = decodeUtf8Strict(bytes);
        String encodedResponse = JSON.writeValueAsString(
          JSON.createObjectNode().put("ok", true).put("status", response.statusCode()).put("body", responseBody));
        if (encodedResponse.length() > MAX_BODY
            || encodedResponse.getBytes(StandardCharsets.UTF_8).length > MAX_BODY) {
          throw new IllegalStateException("host HTTP bridge response envelope too large");
        }
        return encodedResponse;
      } catch (Exception error) {
        System.err.println("host HTTP bridge failure: " + safe(error));
        try {
          return JSON.writeValueAsString(JSON.createObjectNode().put("ok", false).put("error", "host HTTP bridge request failed"));
        } catch (Exception ignored) {
          return "{\"ok\":false,\"error\":\"host HTTP bridge request failed\"}";
        }
      }
    }

    static URI targetUri(URI base, String method, String path, String query) throws URISyntaxException {
      if (!HTTP_METHODS.contains(method)) throw new IllegalArgumentException("unsupported HTTP method");
      if (!path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("relative HTTP path required");
      }
      String lower = path.toLowerCase(Locale.ROOT);
      if (lower.contains("%25") || lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) {
        throw new IllegalArgumentException("encoded path traversal is not allowed");
      }
      URI supplied = URI.create(path);
      if (supplied.isAbsolute() || supplied.getRawAuthority() != null
          || supplied.getRawQuery() != null || supplied.getRawFragment() != null) {
        throw new IllegalArgumentException("relative HTTP path required");
      }

      String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
      URI target = new URI(
        base.getScheme(), null, base.getHost(), base.getPort(),
        basePath + path, query.isEmpty() ? null : query, null
      ).normalize();
      String allowedPrefix = basePath.isEmpty() ? "/" : basePath + "/";
      if (!(target.getPath().equals(basePath) || target.getPath().startsWith(allowedPrefix))) {
        throw new IllegalArgumentException("HTTP base path escape");
      }
      if (!sameOrigin(base, target)) throw new IllegalArgumentException("HTTP origin escape");
      if (target.toASCIIString().length() > MAX_BRIDGE_URL) throw new IllegalArgumentException("HTTP target too long");
      return target;
    }

    static String query(JsonNode query) {
      if (query == null || !query.isObject() || query.isEmpty()) return "";
      List<String> pairs = new ArrayList<>();
      query.fields().forEachRemaining(entry -> {
        String key = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);
        JsonNode value = entry.getValue();
        if (value.isArray()) {
          value.forEach(item -> pairs.add(key + "=" + URLEncoder.encode(item.asText(), StandardCharsets.UTF_8)));
        } else {
          pairs.add(key + "=" + URLEncoder.encode(value.asText(), StandardCharsets.UTF_8));
        }
      });
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

  static String decodeUtf8Strict(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString();
  }

  static boolean isLoopbackHost(String host) {
    if (host == null) return false;
    String normalized = host.toLowerCase(Locale.ROOT);
    return "localhost".equals(normalized)
      || "127.0.0.1".equals(normalized)
      || "::1".equals(normalized)
      || "[::1]".equals(normalized)
      || "0:0:0:0:0:0:0:1".equals(normalized);
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
    String safeMessage = message == null ? "" : message.replaceAll("[\\r\\n\\t]+", " ");
    if (safeMessage.length() > 512) safeMessage = safeMessage.substring(0, 512);
    return current.getClass().getSimpleName() + (safeMessage.isEmpty() ? "" : ": " + safeMessage);
  }
}
