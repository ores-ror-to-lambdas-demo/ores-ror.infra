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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
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
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

public final class SupervisorMain {
  static final ObjectMapper JSON = new ObjectMapper();
  static final int MAX_BODY = 1024 * 1024;
  static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

  private SupervisorMain() {}

  public static void main(String[] args) throws Exception {
    Settings s = Settings.fromEnv();
    try (Cluster cluster = new Cluster(s)) {
      int ingressThreads = Math.max(4, s.maxCells * s.workersPerCell);
      ThreadPoolExecutor ingress = new ThreadPoolExecutor(
        ingressThreads,
        ingressThreads,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(ingressThreads * 16),
        named("ingress"),
        new ThreadPoolExecutor.CallerRunsPolicy()
      );
      HttpServer server = HttpServer.create(new InetSocketAddress(s.bindHost, s.port), 128);
      server.setExecutor(ingress);
      server.createContext("/", ex -> handle(ex, cluster));
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        server.stop(1);
        ingress.shutdown();
        cluster.close();
      }, "ror-shutdown"));
      server.start();
      System.out.printf(
        "Graal Lambda cluster listening on http://%s:%d cells=%d threads/cell=%d%n",
        s.bindHost, s.port, s.minCells, s.workersPerCell
      );
      Thread.currentThread().join();
    }
  }

  static void handle(HttpExchange ex, Cluster cluster) throws IOException {
    try {
      byte[] input = ex.getRequestBody().readNBytes(MAX_BODY + 1);
      if (input.length > MAX_BODY) {
        sendError(ex, 413, "request body too large");
        return;
      }

      ObjectNode req = JSON.createObjectNode();
      req.put("request_id", requestId(ex.getRequestHeaders().getFirst("x-request-id")));
      req.put("method", ex.getRequestMethod());
      req.put("path", ex.getRequestURI().getPath());
      req.put("query_string", ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery());
      req.put("body", new String(input, StandardCharsets.UTF_8));
      req.put("content_type", valueOr(ex.getRequestHeaders().getFirst("content-type"), "application/json"));

      JsonNode result = cluster.invoke(req);
      int status = result.path("status").asInt(500);
      result.path("headers").fields().forEachRemaining(header -> {
        String name = header.getKey();
        if (header.getValue().isTextual() && safeResponseHeader(name)) {
          ex.getResponseHeaders().set(name, header.getValue().asText());
        }
      });

      byte[] body = result.path("body").asText("").getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(status, body.length);
      ex.getResponseBody().write(body);
    } catch (RejectedExecutionException e) {
      sendError(ex, 503, "Graal workers saturated");
    } catch (TimeoutException e) {
      sendError(ex, 504, "Graal request timed out");
    } catch (Exception e) {
      System.err.println("request failure: " + safe(e));
      sendError(ex, 500, "internal error");
    } finally {
      ex.close();
    }
  }

  static boolean safeResponseHeader(String name) {
    String normalized = name.toLowerCase(Locale.ROOT);
    return normalized.equals("content-type")
      || normalized.equals("cache-control")
      || normalized.equals("etag")
      || normalized.equals("location")
      || normalized.equals("x-request-id");
  }

  static void sendError(HttpExchange ex, int status, String message) throws IOException {
    byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("error", message));
    ex.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(status, body.length);
    ex.getResponseBody().write(body);
  }

  static final class Settings {
    final String bindHost;
    final int port;
    final Path appRoot;
    final Path bootstrap;
    final String dataUrl;
    final String dataToken;
    final int minCells;
    final int maxCells;
    final int workersPerCell;
    final long maxAgeMs;
    final long idleMs;
    final long drainMs;
    final long requestTimeoutMs;

    Settings(
      String bindHost,
      int port,
      Path appRoot,
      String dataUrl,
      String dataToken,
      int minCells,
      int maxCells,
      int workersPerCell,
      long maxAgeMs,
      long idleMs,
      long drainMs,
      long requestTimeoutMs
    ) {
      this.bindHost = bindHost;
      this.port = port;
      this.appRoot = appRoot;
      this.bootstrap = appRoot.resolve("graal/bootstrap.rb").normalize();
      this.dataUrl = dataUrl;
      this.dataToken = dataToken;
      this.minCells = minCells;
      this.maxCells = maxCells;
      this.workersPerCell = workersPerCell;
      this.maxAgeMs = maxAgeMs;
      this.idleMs = idleMs;
      this.drainMs = drainMs;
      this.requestTimeoutMs = requestTimeoutMs;
      validate();
    }

    static Settings fromEnv() {
      int min = integer("MIN_ISOLATES", 1, 1, 32);
      return new Settings(
        env("BIND_HOST", "127.0.0.1"),
        integer("PORT", 8080, 1, 65535),
        Path.of(env("APP_ROOT", "../ores-ror.rb")).toAbsolutePath().normalize(),
        env("DATA_API_URL", "http://127.0.0.1:8787/v1"),
        env("DATA_API_TOKEN", ""),
        min,
        integer("MAX_ISOLATES", 4, min, 64),
        integer("ISOLATE_MAX_CONCURRENCY", 5, 1, 5),
        1000L * integer("ISOLATE_MAX_AGE_SECONDS", 1800, 60, 1800),
        1000L * integer("ISOLATE_IDLE_SECONDS", 300, 30, 300),
        1000L * integer("ISOLATE_DRAIN_SECONDS", 30, 1, 300),
        1000L * integer("REQUEST_TIMEOUT_SECONDS", 15, 1, 300)
      );
    }

    static Settings test(Path root, int cells, int workers) {
      return new Settings(
        "127.0.0.1",
        0,
        root.toAbsolutePath().normalize(),
        "http://127.0.0.1:9/v1",
        "",
        cells,
        cells,
        workers,
        1_800_000,
        300_000,
        5_000,
        15_000
      );
    }

    void validate() {
      if (!Files.isRegularFile(bootstrap)) {
        throw new IllegalArgumentException("missing graal/bootstrap.rb: " + bootstrap);
      }
      if (!Files.isRegularFile(appRoot.resolve("generated/lambda/entrypoint.rb"))) {
        throw new IllegalArgumentException("missing generated Lambda entrypoint under APP_ROOT: " + appRoot);
      }

      URI uri = URI.create(dataUrl);
      if (!List.of("http", "https").contains(uri.getScheme())) {
        throw new IllegalArgumentException("DATA_API_URL must use http or https");
      }
      if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException("DATA_API_URL must be an origin/path without userinfo, query, or fragment");
      }
    }

    static int integer(String name, int defaultValue, int min, int max) {
      int value = Integer.parseInt(env(name, Integer.toString(defaultValue)));
      if (value < min || value > max) {
        throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
      }
      return value;
    }

    static String env(String name, String defaultValue) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? defaultValue : value;
    }
  }

  static final class Cluster implements AutoCloseable {
    final Settings s;
    final HttpBridge http;
    final List<Cell> cells = new ArrayList<>();
    final ScheduledExecutorService maintenance = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(named("maintenance"));
    long nextId;
    boolean closed;

    Cluster(Settings s) throws Exception {
      this.s = s;
      this.http = new HttpBridge(s.dataUrl, s.dataToken);
      synchronized (this) {
        ensureMin();
      }
      maintenance.scheduleAtFixedRate(this::maintainSafe, 1, 1, TimeUnit.SECONDS);
    }

    JsonNode invoke(ObjectNode request) throws Exception {
      Cell cell;
      synchronized (this) {
        maintain(System.currentTimeMillis());
        cell = select();
      }

      CompletableFuture<JsonNode> future = cell.submit(request.deepCopy());
      try {
        return future.get(s.requestTimeoutMs, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        cell.retire();
        synchronized (this) {
          ensureMin();
        }
        throw e;
      } catch (ExecutionException e) {
        cell.retire();
        synchronized (this) {
          ensureMin();
        }
        Throwable cause = e.getCause();
        if (cause instanceof Exception exception) {
          throw exception;
        }
        throw e;
      }
    }

    synchronized int cellCount() {
      return cells.size();
    }

    synchronized int acceptingCellCount() {
      return (int) cells.stream().filter(Cell::accepting).count();
    }

    synchronized int workerCount() {
      return cells.stream().filter(Cell::accepting).mapToInt(cell -> cell.s.workersPerCell).sum();
    }

    Cell select() throws Exception {
      List<Cell> open = cells.stream()
        .filter(Cell::accepting)
        .sorted(Comparator.comparingInt(Cell::load))
        .toList();

      if (open.isEmpty()) {
        Cell created = create();
        cells.add(created);
        return created;
      }

      Cell selected = open.get(0);
      if (selected.load() >= s.workersPerCell && open.size() < s.maxCells) {
        Cell created = create();
        cells.add(created);
        return created;
      }
      return selected;
    }

    void maintainSafe() {
      try {
        synchronized (this) {
          if (!closed) {
            maintain(System.currentTimeMillis());
          }
        }
      } catch (Throwable e) {
        System.err.println("maintenance failure: " + safe(e));
      }
    }

    void maintain(long now) throws Exception {
      int idleBudget = Math.max(0, acceptingCellCount() - s.minCells);
      for (Cell cell : cells) {
        if (!cell.accepting()) {
          continue;
        }
        if (cell.old(now)) {
          cell.retire(now);
        } else if (idleBudget > 0 && cell.idle(now)) {
          cell.retire(now);
          idleBudget--;
        }
      }

      ensureMin();

      List<Cell> gone = new ArrayList<>();
      for (Cell cell : cells) {
        if (!cell.retiring()) {
          continue;
        }
        if (cell.load() == 0) {
          cell.closeCleanly();
          gone.add(cell);
        } else if (cell.drainExpired(now)) {
          System.err.println("force-closing drained-out isolate " + cell.id + " load=" + cell.load());
          cell.forceClose();
          gone.add(cell);
        }
      }
      cells.removeAll(gone);
      ensureMin();
    }

    void ensureMin() throws Exception {
      while (!closed && acceptingCellCount() < s.minCells) {
        cells.add(create());
      }
    }

    Cell create() throws Exception {
      return new Cell("cell-" + (++nextId), s, http);
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      closed = true;
      maintenance.shutdownNow();
      for (Cell cell : cells) {
        cell.closeAfterDrain();
      }
      cells.clear();
    }
  }

  static final class Cell implements AutoCloseable {
    final String id;
    final Settings s;
    final Engine engine;
    final Context context;
    final Value invoke;
    final ThreadPoolExecutor pool;
    final Set<String> hostThreads = ConcurrentHashMap.newKeySet();
    final long born = System.currentTimeMillis();
    final AtomicLong last = new AtomicLong(born);
    final AtomicLong retiredAt = new AtomicLong();
    final AtomicInteger active = new AtomicInteger();
    volatile boolean open = true;
    volatile boolean closed;

    Cell(String id, Settings s, HttpBridge http) throws Exception {
      this.id = id;
      this.s = s;
      this.engine = Engine.newBuilder("ruby").build();

      Context candidate = Context.newBuilder("ruby")
        .engine(engine)
        .allowExperimentalOptions(true)
        .allowAllAccess(false)
        .allowHostAccess(HostAccess.EXPLICIT)
        .allowHostClassLookup(name -> false)
        .allowHostClassLoading(false)
        .allowNativeAccess(false)
        .allowCreateProcess(false)
        .allowCreateThread(false)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .allowIO(sandboxedIO(s.appRoot))
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .option("ruby.platform-native", "false")
        .option("ruby.cexts", "false")
        .currentWorkingDirectory(s.appRoot)
        .build();

      Value candidateInvoke;
      try {
        Value bindings = candidate.getBindings("ruby");
        bindings.putMember("gs_http", (ProxyExecutable) http::call);
        bindings.putMember("gs_app_root", s.appRoot.toString());

        Source boot = Source.newBuilder(
          "ruby",
          Files.readString(s.bootstrap, StandardCharsets.UTF_8),
          "graal/bootstrap.rb"
        ).interactive(true).cached(true).build();

        candidateInvoke = candidate.eval(boot);
        if (!candidateInvoke.canExecute()) {
          throw new IllegalStateException("bootstrap did not return Lambda invoker");
        }
      } catch (Exception e) {
        candidate.close(true);
        engine.close();
        throw e;
      }

      this.context = candidate;
      this.invoke = candidateInvoke;
      this.pool = new ThreadPoolExecutor(
        s.workersPerCell,
        s.workersPerCell,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(s.workersPerCell * 8),
        named(id),
        new ThreadPoolExecutor.AbortPolicy()
      );
    }

    CompletableFuture<JsonNode> submit(ObjectNode request) {
      if (!accepting()) {
        throw new RejectedExecutionException("cell retiring");
      }

      last.set(System.currentTimeMillis());
      CompletableFuture<JsonNode> future = new CompletableFuture<>();
      pool.execute(() -> {
        active.incrementAndGet();
        hostThreads.add(Thread.currentThread().getName());
        try {
          Value value = invoke.execute(JSON.writeValueAsString(request));
          future.complete(JSON.readTree(value.asString()));
        } catch (Throwable e) {
          future.completeExceptionally(e);
        } finally {
          active.decrementAndGet();
          last.set(System.currentTimeMillis());
        }
      });
      return future;
    }

    int load() {
      return active.get() + pool.getQueue().size();
    }

    boolean accepting() {
      return open && !closed;
    }

    boolean retiring() {
      return !open && !closed;
    }

    boolean old(long now) {
      return now - born >= s.maxAgeMs;
    }

    boolean idle(long now) {
      return load() == 0 && now - last.get() >= s.idleMs;
    }

    boolean drainExpired(long now) {
      long retired = retiredAt.get();
      return retired > 0 && now - retired >= s.drainMs;
    }

    void retire() {
      retire(System.currentTimeMillis());
    }

    void retire(long now) {
      if (retiredAt.compareAndSet(0, now)) {
        open = false;
        pool.shutdown();
      }
    }

    int contextIdentity() {
      return System.identityHashCode(context);
    }

    int hostThreadCount() {
      return hostThreads.size();
    }

    void closeAfterDrain() {
      retire();
      try {
        if (!pool.awaitTermination(s.drainMs, TimeUnit.MILLISECONDS)) {
          forceClose();
          return;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        forceClose();
        return;
      }
      closeResources(false);
    }

    void closeCleanly() {
      retire();
      if (load() != 0) {
        throw new IllegalStateException("cell busy");
      }
      closeResources(false);
    }

    void forceClose() {
      open = false;
      pool.shutdownNow();
      closeResources(true);
    }

    @Override
    public void close() {
      closeAfterDrain();
    }

    synchronized void closeResources(boolean cancelIfExecuting) {
      if (closed) {
        return;
      }
      closed = true;
      context.close(cancelIfExecuting);
      engine.close();
    }
  }

  static IOAccess sandboxedIO(Path appRoot) throws IOException {
    Path root = appRoot.toRealPath();
    FileSystem readOnlyHost = FileSystem.newReadOnlyFileSystem(FileSystem.newDefaultFileSystem());
    FileSystem appOnly = FileSystem.newCompositeFileSystem(
      FileSystem.newDenyIOFileSystem(),
      FileSystem.Selector.of(readOnlyHost, path -> withinRoot(root, path))
    );
    FileSystem withLanguageHome = FileSystem.allowLanguageHomeAccess(appOnly);
    return IOAccess.newBuilder()
      .fileSystem(withLanguageHome)
      .allowHostSocketAccess(false)
      .build();
  }

  static boolean withinRoot(Path root, Path path) {
    try {
      Path candidate = path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
      if (!candidate.startsWith(root)) {
        return false;
      }
      return !Files.exists(candidate) || candidate.toRealPath().startsWith(root);
    } catch (IOException | SecurityException e) {
      return false;
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
        JsonNode request = JSON.readTree(args[0].asString());
        String method = request.path("method").asText("GET").toUpperCase(Locale.ROOT);
        String path = request.path("path").asText();
        String query = query(request.path("query"));
        URI target = targetUri(base, method, path, query);

        JsonNode bodyNode = request.get("body");
        String body = bodyNode == null || bodyNode.isNull() ? "" : JSON.writeValueAsString(bodyNode);

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
          .timeout(Duration.ofSeconds(10))
          .header("accept", "application/json")
          .header("content-type", "application/json");
        if (token != null && !token.isEmpty()) {
          builder.header("authorization", "Bearer " + token);
        }
        builder.method(
          method,
          body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)
        );

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (InputStream input = response.body()) {
          bytes = input.readNBytes(MAX_BODY + 1);
        }
        if (bytes.length > MAX_BODY) {
          throw new IllegalStateException("HTTP response too large");
        }

        return JSON.writeValueAsString(JSON.createObjectNode()
          .put("ok", true)
          .put("status", response.statusCode())
          .put("body", new String(bytes, StandardCharsets.UTF_8)));
      } catch (Exception e) {
        System.err.println("HTTP bridge failure: " + safe(e));
        try {
          return JSON.writeValueAsString(JSON.createObjectNode()
            .put("ok", false)
            .put("error", "host HTTP bridge request failed"));
        } catch (Exception ignored) {
          return "{\"ok\":false,\"error\":\"host HTTP bridge request failed\"}";
        }
      }
    }

    static URI targetUri(URI base, String method, String path, String query) throws URISyntaxException {
      if (!HTTP_METHODS.contains(method)) {
        throw new IllegalArgumentException("unsupported HTTP method");
      }
      if (!path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("relative HTTP path required");
      }

      String lower = path.toLowerCase(Locale.ROOT);
      if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) {
        throw new IllegalArgumentException("encoded path traversal is not allowed");
      }

      URI supplied = URI.create(path);
      if (supplied.isAbsolute() || supplied.getRawAuthority() != null
          || supplied.getRawQuery() != null || supplied.getRawFragment() != null) {
        throw new IllegalArgumentException("relative HTTP path required");
      }

      String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
      String targetPath = basePath + path;
      URI target = new URI(
        base.getScheme(),
        null,
        base.getHost(),
        base.getPort(),
        targetPath,
        query.isEmpty() ? null : query,
        null
      ).normalize();

      String allowedPrefix = basePath.isEmpty() ? "/" : basePath + "/";
      if (!(target.getPath().equals(basePath) || target.getPath().startsWith(allowedPrefix))) {
        throw new IllegalArgumentException("HTTP base path escape");
      }
      if (!sameOrigin(base, target)) {
        throw new IllegalArgumentException("HTTP origin escape");
      }
      return target;
    }

    static String query(JsonNode query) {
      if (query == null || !query.isObject() || query.isEmpty()) {
        return "";
      }

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

    static boolean sameOrigin(URI a, URI b) {
      return a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && port(a) == port(b);
    }

    static int port(URI uri) {
      return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
  }

  static ThreadFactory named(String prefix) {
    AtomicInteger number = new AtomicInteger();
    return runnable -> new Thread(runnable, prefix + "-" + number.incrementAndGet());
  }

  static String requestId(String value) {
    return value != null && value.matches("[A-Za-z0-9._:-]{1,128}")
      ? value
      : "ores-request-" + UUID.randomUUID();
  }

  static String valueOr(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }

  static String safe(Throwable error) {
    Throwable root = error.getCause() != null ? error.getCause() : error;
    String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    message = message.replaceAll("[\\r\\n\\t]+", " ");
    return message.length() > 512 ? message.substring(0, 512) : message;
  }
}
