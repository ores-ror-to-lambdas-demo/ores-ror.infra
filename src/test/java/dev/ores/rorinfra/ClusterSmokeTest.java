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
      assertTrue(workerThreads.size() > 1, "one Context should be entered by multiple reusable host workers");
      assertTrue(workerThreads.size() <= 5, "Context worker pool must remain bounded at five threads");
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

    ObjectNode wrongSharedSource = manifest.deepCopy();
    ObjectNode firstUnit = (ObjectNode) wrongSharedSource.withArray("isolate_units").get(0);
    firstUnit.withArray("sources").set(
      0, SupervisorMain.JSON.getNodeFactory().textNode("generated/graal/not-common.rb"));
    assertThrows(IllegalArgumentException.class, () -> settings.parseUnits(wrongSharedSource, "route"));

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
