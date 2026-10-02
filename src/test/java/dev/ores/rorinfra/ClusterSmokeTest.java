package dev.ores.rorinfra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

final class ClusterSmokeTest {
  @Test
  void routeGranularityUsesOneLongLivedContextWithBoundedAdmission() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var settings = SupervisorMain.Settings.test(root, "route", 5);

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      assertEquals(0, cluster.contextCount());
      assertEquals(0, cluster.workerCount());
      assertEquals(0, cluster.admissionCapacity());

      Set<String> contextIds = ConcurrentHashMap.newKeySet();
      Set<String> workerThreads = ConcurrentHashMap.newKeySet();
      for (int i = 0; i < 100; i++) {
        JsonNode response = cluster.invoke(request("gha-" + i, "GET", "/healthz"));
        assertEquals(200, response.path("status").asInt(), response.toString());
        JsonNode body = SupervisorMain.JSON.readTree(response.path("body").asText());
        assertTrue(body.path("ok").asBoolean(), body.toString());
        assertEquals("ores-ror.rb", body.path("service").asText());
        assertTrue(body.path("request_id").asText().startsWith("gha-"), body.toString());
        contextIds.add(response.path("headers").path("x-ores-graal-context-id").asText());
        workerThreads.add(response.path("headers").path("x-ores-graal-worker-thread").asText());
        assertTrue(response.path("headers").path("x-ores-graal-isolate-key").asText().startsWith("route:"));
      }

      assertEquals(1, contextIds.size(), "one route must keep one Context across request volume");
      assertFalse(workerThreads.isEmpty());
      assertTrue(workerThreads.size() <= cluster.sharedThreadPoolSize(), "host threads must come from the bounded process-wide pool");
      assertEquals(8, cluster.sharedThreadPoolSize());
      assertTrue(cluster.largestSharedThreadPoolSize() <= 8);
      assertEquals(1, cluster.contextCount(), "one requested route should own one long-lived Context");
      assertEquals(1, cluster.workerCount());
      assertEquals(5, cluster.admissionCapacity());
      var healthRoute = settings.resolveRoute("GET", "/healthz");
      var health = cluster.cells.get(settings.unitKey(healthRoute));
      assertTrue(health.maxConcurrentGuestEntries() >= 1);
      assertTrue(health.maxConcurrentGuestEntries() <= 5, "one Context must never exceed five concurrent guest entries");

      ExecutorService parallel = Executors.newFixedThreadPool(5);
      try {
        Set<String> rubyThreadIds = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
          entries.add(CompletableFuture.runAsync(() -> {
            try {
              rubyThreadIds.add(health.evalForTest("sleep 0.05; Thread.current.object_id.to_s"));
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          }, parallel));
        }
        for (var entry : entries) entry.get();
        assertTrue(rubyThreadIds.size() > 1, "shared Context must support entry from multiple host threads");
        assertTrue(rubyThreadIds.size() <= 5);
      } finally {
        parallel.shutdownNow();
      }
    }
  }

  @Test
  void oneProcessWideHostThreadCanEnterMultipleDistinctWorkers() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var placement = new SupervisorMain.WorkerPlacement("route", "lazy", java.util.Map.of(), java.util.Map.of());
    var settings = SupervisorMain.Settings.test(root, placement, 1, 1);

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      var healthRoute = settings.resolveRoute("GET", "/healthz");
      var ordersRoute = settings.resolveRoute("GET", "/orders/demo");
      assertTrue(healthRoute != null && ordersRoute != null);

      var health = cluster.create(settings.unitFor(healthRoute));
      var orders = cluster.create(settings.unitFor(ordersRoute));
      try {
        assertNotEquals(health.contextId, orders.contextId);
        String healthThread = health.hostThreadNameForTest();
        String ordersThread = orders.hostThreadNameForTest();
        assertEquals(healthThread, ordersThread, "the same process-wide host thread should be reusable across distinct Contexts");
        assertTrue(healthThread.startsWith("graal-guest-"));
        assertEquals(1, cluster.sharedThreadPoolSize());
        assertEquals(1, cluster.largestSharedThreadPoolSize());
      } finally {
        health.close();
        orders.close();
      }
    }
  }

  @Test
  void sequentialGuestEntriesReleaseAdmissionBeforeCompletionIsObserved() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var placement = new SupervisorMain.WorkerPlacement("route", "lazy", java.util.Map.of(), java.util.Map.of());
    var settings = SupervisorMain.Settings.test(root, placement, 1, 4);

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      var healthRoute = settings.resolveRoute("GET", "/healthz");
      var health = cluster.create(settings.unitFor(healthRoute));
      try {
        for (int i = 0; i < 100; i++) {
          assertEquals(Integer.toString(i), health.evalForTest(i + ".to_s"));
        }
        assertEquals(0, health.load());
      } finally {
        health.close();
      }
    }
  }

  @Test
  void closedClusterRejectsNewInvocationsAndDoesNotRecreateWorkers() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var settings = SupervisorMain.Settings.test(root, "route", 1);
    var cluster = new SupervisorMain.Cluster(settings);

    JsonNode first = cluster.invoke(request("before-close", "GET", "/healthz"));
    assertEquals(200, first.path("status").asInt());
    assertEquals(1, cluster.workerCount());

    cluster.close();
    assertEquals(0, cluster.workerCount());
    assertThrows(
      RejectedExecutionException.class,
      () -> cluster.invoke(request("after-close", "GET", "/healthz"))
    );
    assertEquals(0, cluster.workerCount(), "closed cluster must not lazily recreate a worker");
  }

  @Test
  void hardCancellingOneWorkerDoesNotShutdownSharedPoolOrOtherWorkers() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var placement = new SupervisorMain.WorkerPlacement("route", "lazy", java.util.Map.of(), java.util.Map.of());
    var settings = SupervisorMain.Settings.test(root, placement, 1, 1);

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      var healthRoute = settings.resolveRoute("GET", "/healthz");
      var ordersRoute = settings.resolveRoute("GET", "/orders/demo");
      var health = cluster.create(settings.unitFor(healthRoute));
      var orders = cluster.create(settings.unitFor(ordersRoute));
      try {
        assertEquals("1", orders.evalForTest("1.to_s"));
        health.hardCancel("test cancellation");
        assertTrue(health.closed());
        assertFalse(cluster.guestExecutor.isShutdown(), "worker cancellation must not own or stop the process-wide pool");
        assertEquals("2", orders.evalForTest("2.to_s"), "unrelated worker must remain usable after another worker is cancelled");
      } finally {
        if (!health.closed()) health.close();
        if (!orders.closed()) orders.close();
      }
    }
  }

  @Test
  void perIsolateAdmissionRemainsBoundedOnLargerSharedPool() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var placement = new SupervisorMain.WorkerPlacement("route", "lazy", java.util.Map.of(), java.util.Map.of());
    var settings = SupervisorMain.Settings.test(root, placement, 2, 8);

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      var healthRoute = settings.resolveRoute("GET", "/healthz");
      var health = cluster.create(settings.unitFor(healthRoute));
      try {
        var first = health.evalForTestAsync("sleep 0.1; 'first'");
        var second = health.evalForTestAsync("sleep 0.1; 'second'");
        assertThrows(
          RejectedExecutionException.class,
          () -> health.evalForTestAsync("'third'")
        );
        assertEquals("first", first.get());
        assertEquals("second", second.get());
        assertEquals(2, health.maxConcurrentGuestEntries());
        assertTrue(cluster.largestSharedThreadPoolSize() <= 8);
      } finally {
        health.close();
      }
    }
  }

  @Test
  void mixedWorkerPlacementSharesOnlyExplicitlyCompatibleRoutes() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var placement = new SupervisorMain.WorkerPlacement(
      "route",
      "lazy",
      java.util.Map.of(),
      java.util.Map.of(
        "GET /orders/:id", "group:orders",
        "POST /orders/:id/cancel", "group:orders"
      )
    );
    var settings = SupervisorMain.Settings.test(root, placement, 2);

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      assertEquals(0, cluster.workerCount(), "lazy placement must not pre-create workers");

      JsonNode show = cluster.invoke(request("mixed-show", "GET", "/orders/demo"));
      JsonNode cancel = cluster.invoke(request("mixed-cancel", "POST", "/orders/demo/cancel"));
      String showContext = show.path("headers").path("x-ores-graal-context-id").asText();
      String cancelContext = cancel.path("headers").path("x-ores-graal-context-id").asText();
      assertEquals(showContext, cancelContext, "explicitly assigned routes should share the selected generated worker unit");
      assertEquals(1, cluster.workerCount());

      JsonNode health = cluster.invoke(request("mixed-health", "GET", "/healthz"));
      String healthContext = health.path("headers").path("x-ores-graal-context-id").asText();
      assertNotEquals(showContext, healthContext, "unassigned routes keep the safe per-handler worker default");
      assertEquals(2, cluster.workerCount());
    }
  }

  @Test
  void workerPlacementRejectsConflictingRouteSelectors() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var probe = SupervisorMain.Settings.test(root, "route", 1);
    var health = probe.resolveRoute("GET", "/healthz");
    assertTrue(health != null);

    var placement = new SupervisorMain.WorkerPlacement(
      "route",
      "lazy",
      java.util.Map.of(health.routeId, "route:" + health.routeId),
      java.util.Map.of("GET /healthz", "group:healthz")
    );

    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.Settings.test(root, placement, 1)
    );
  }

  @Test
  void workerPlacementRejectsUnitsThatDoNotContainTheSelectedRoute() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var placement = new SupervisorMain.WorkerPlacement(
      "route",
      "lazy",
      java.util.Map.of(),
      java.util.Map.of("GET /healthz", "group:orders")
    );

    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.Settings.test(root, placement, 1)
    );
  }

  @Test
  void routeAndGroupGranularityHaveExpectedIsolationBoundaries() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();

    try (var routeCluster = new SupervisorMain.Cluster(SupervisorMain.Settings.test(root, "route", 2))) {
      JsonNode show = routeCluster.invoke(request("route-show", "GET", "/orders/demo"));
      JsonNode cancel = routeCluster.invoke(request("route-cancel", "POST", "/orders/demo/cancel"));
      String showContext = show.path("headers").path("x-ores-graal-context-id").asText();
      String cancelContext = cancel.path("headers").path("x-ores-graal-context-id").asText();
      assertTrue(!showContext.isBlank() && !cancelContext.isBlank());
      assertNotEquals(showContext, cancelContext, "distinct routes must have distinct route-isolate Contexts");
    }

    try (var groupCluster = new SupervisorMain.Cluster(SupervisorMain.Settings.test(root, "group", 2))) {
      JsonNode show = groupCluster.invoke(request("group-show", "GET", "/orders/demo"));
      JsonNode cancel = groupCluster.invoke(request("group-cancel", "POST", "/orders/demo/cancel"));
      String showContext = show.path("headers").path("x-ores-graal-context-id").asText();
      String cancelContext = cancel.path("headers").path("x-ores-graal-context-id").asText();
      assertEquals(showContext, cancelContext, "routes in one group must share exactly one group-isolate Context");
      assertTrue(show.path("headers").path("x-ores-graal-isolate-key").asText().startsWith("group:orders"));
    }
  }

  @Test
  void enforceableGuestHostCapabilitiesRemainBlocked() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    try (var cluster = new SupervisorMain.Cluster(SupervisorMain.Settings.test(root, "route", 1))) {
      JsonNode health = cluster.invoke(request("capabilities", "GET", "/healthz"));
      assertEquals(200, health.path("status").asInt());
      var healthRoute = cluster.settings.resolveRoute("GET", "/healthz");
      var worker = cluster.cells.get(cluster.settings.unitKey(healthRoute));

      String thread = worker.evalForTest("begin; Thread.new { 1 }.join; 'allowed'; rescue Exception => e; e.class.name; end"
      );
      assertNotEquals("allowed", thread, "guest-created threads must remain blocked");

      String hostClass = worker.evalForTest("begin; Java.type('java.lang.System'); 'allowed'; rescue Exception => e; e.class.name; end"
      );
      assertNotEquals("allowed", hostClass, "host class lookup must remain blocked");

      String fileRead = worker.evalForTest("begin; File.read('/etc/passwd'); 'allowed'; rescue Exception => e; e.class.name; end"
      );
      assertNotEquals("allowed", fileRead, "guest filesystem reads must remain blocked");

      String process = worker.evalForTest("begin; Process.spawn('true'); 'allowed'; rescue Exception => e; e.class.name; end"
      );
      assertNotEquals("allowed", process, "guest process creation must remain blocked");
    }
  }

  @Test
  void unknownRouteDoesNotAllocateAnIsolate() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    try (var cluster = new SupervisorMain.Cluster(SupervisorMain.Settings.test(root, "route", 2))) {
      int before = cluster.contextCount();
      JsonNode response = cluster.invoke(request("missing", "GET", "/does-not-exist"));
      assertEquals(404, response.path("status").asInt());
      assertEquals(before, cluster.contextCount());
    }
  }

  @Test
  void hostHttpBridgeCannotEscapeConfiguredOriginOrBasePath() throws Exception {
    URI base = URI.create("https://data.example.test/v1/");

    URI target = SupervisorMain.HttpBridge.targetUri(base, "GET", "/users/abc", "a=1");
    assertEquals("https://data.example.test/v1/users/abc?a=1", target.toString());

    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.HttpBridge.targetUri(base, "TRACE", "/users/abc", "")
    );
    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.HttpBridge.targetUri(base, "GET", "/../admin", "")
    );
    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.HttpBridge.targetUri(base, "GET", "/%2e%2e/admin", "")
    );
    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.HttpBridge.targetUri(base, "GET", "/%252e%252e/admin", "")
    );
    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.HttpBridge.targetUri(base, "GET", "//evil.example/path", "")
    );
  }

  @Test
  void responseHeadersRejectHopByHopInjectionAndOversizedValues() {
    assertTrue(SupervisorMain.safeResponseHeader("content-type", "application/json"));
    assertFalse(SupervisorMain.safeResponseHeader("content-length", "42"));
    assertFalse(SupervisorMain.safeResponseHeader("transfer-encoding", "chunked"));
    assertFalse(SupervisorMain.safeResponseHeader("x-test", "ok\r\ninjected: true"));
    assertFalse(SupervisorMain.safeResponseHeader("bad header", "x"));
    assertFalse(SupervisorMain.safeResponseHeader("x-test", "a".repeat(8193)));
  }

  @Test
  void tokenOverCleartextIsOnlyAllowedForLoopbackDataApi() {
    assertTrue(SupervisorMain.isLoopbackHost("localhost"));
    assertTrue(SupervisorMain.isLoopbackHost("127.0.0.1"));
    assertTrue(SupervisorMain.isLoopbackHost("::1"));
    assertFalse(SupervisorMain.isLoopbackHost("data.example.test"));
  }

  @Test
  void strictUtf8DecoderRejectsMalformedInput() throws Exception {
    assertEquals("hello ✓", SupervisorMain.decodeUtf8Strict("hello ✓".getBytes(StandardCharsets.UTF_8)));
    assertThrows(CharacterCodingException.class, () -> SupervisorMain.decodeUtf8Strict(new byte[] {(byte) 0xC3, (byte) 0x28}));
  }

  @Test
  void manifestIdentityAndUnitSourceContractFailClosed() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var settings = SupervisorMain.Settings.test(root, "route", 2);
    ObjectNode manifest = (ObjectNode) SupervisorMain.JSON.readTree(
      Files.readString(root.resolve("generated/graal/manifest.json"), StandardCharsets.UTF_8));

    ObjectNode wrongApplication = manifest.deepCopy();
    wrongApplication.put("application", "other-app");
    assertThrows(IllegalArgumentException.class, () -> settings.validateManifest(wrongApplication));

    ObjectNode wrongPoolScope = manifest.deepCopy();
    wrongPoolScope.put("host_thread_pool_scope", "per-context");
    assertThrows(IllegalArgumentException.class, () -> settings.validateManifest(wrongPoolScope));

    ObjectNode noCrossIsolateReuse = manifest.deepCopy();
    noCrossIsolateReuse.put("host_threads_reused_across_isolates", false);
    assertThrows(IllegalArgumentException.class, () -> settings.validateManifest(noCrossIsolateReuse));

    ObjectNode wrongUnitPool = manifest.deepCopy();
    ((ObjectNode) wrongUnitPool.withArray("isolate_units").get(0)).put("host_thread_pool_scope", "per-context");
    assertThrows(IllegalArgumentException.class, () -> settings.parseUnits(wrongUnitPool));

    ObjectNode wrongSharedSource = manifest.deepCopy();
    ObjectNode firstUnit = (ObjectNode) wrongSharedSource.withArray("isolate_units").get(0);
    firstUnit.withArray("sources").set(
      0, SupervisorMain.JSON.getNodeFactory().textNode("generated/graal/not-common.rb"));
    assertThrows(IllegalArgumentException.class, () -> settings.parseUnits(wrongSharedSource));

    ObjectNode duplicateRouteId = manifest.deepCopy();
    String firstId = duplicateRouteId.withArray("routes").get(0).path("route_id").asText();
    ((ObjectNode) duplicateRouteId.withArray("routes").get(1)).put("route_id", firstId);
    assertThrows(IllegalArgumentException.class, () -> settings.parseRoutes(duplicateRouteId));
  }

  @Test
  void guestResponseEnvelopeAndGeneratedSourceValidationFailClosed() throws Exception {
    ObjectNode valid = SupervisorMain.JSON.createObjectNode();
    valid.putObject("headers").put("content-type", "application/json");
    valid.put("body", "{}");
    SupervisorMain.GraalWorker.validateGuestResponse(valid);

    ObjectNode missingBody = SupervisorMain.JSON.createObjectNode();
    missingBody.putObject("headers");
    assertThrows(IllegalStateException.class, () -> SupervisorMain.GraalWorker.validateGuestResponse(missingBody));

    ObjectNode tooManyHeaders = SupervisorMain.JSON.createObjectNode();
    ObjectNode headers = tooManyHeaders.putObject("headers");
    for (int i = 0; i <= SupervisorMain.MAX_RESPONSE_HEADERS; i++) headers.put("x-test-" + i, "v");
    tooManyHeaders.put("body", "{}");
    assertThrows(IllegalStateException.class, () -> SupervisorMain.GraalWorker.validateGuestResponse(tooManyHeaders));

    Path source = Files.createTempFile("ores-graal-source", ".rb");
    try {
      Files.writeString(source, "ActionController::Base\n", StandardCharsets.UTF_8);
      assertThrows(
        IllegalArgumentException.class,
        () -> SupervisorMain.Settings.readRubySource(source, "adversarial generated source"));
    } finally {
      Files.deleteIfExists(source);
    }
  }

  @Test
  void hostHttpBridgeRejectsOversizedTargets() {
    URI base = URI.create("https://data.example.test/v1/");
    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.HttpBridge.targetUri(base, "GET", "/" + "a".repeat(SupervisorMain.MAX_BRIDGE_URL), "")
    );
  }

  static ObjectNode request(String id, String method, String path) {
    ObjectNode request = SupervisorMain.JSON.createObjectNode();
    request.put("request_id", id);
    request.put("method", method);
    request.put("path", path);
    request.put("query_string", "");
    request.put("body", "");
    request.putObject("headers");
    return request;
  }
}
