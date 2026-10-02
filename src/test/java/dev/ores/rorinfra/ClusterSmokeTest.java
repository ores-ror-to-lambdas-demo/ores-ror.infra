package dev.ores.rorinfra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
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
import org.junit.jupiter.api.io.TempDir;

final class ClusterSmokeTest {
  @Test
  void oneContextServesManyRequestsAcrossReusableHostThreads() throws Exception {
    Path root = appRoot();
    var settings = SupervisorMain.Settings.test(root, 1, 5);

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      assertEquals(1, cluster.cellCount());
      assertEquals(5, cluster.workerCount());
      var cell = cluster.cells.get(0);
      int contextIdentity = cell.contextIdentity();

      ExecutorService clients = Executors.newFixedThreadPool(20);
      Set<String> returnedRequestIds = ConcurrentHashMap.newKeySet();
      try {
        List<CompletableFuture<JsonNode>> calls = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
          int n = i;
          calls.add(CompletableFuture.supplyAsync(() -> {
            try {
              String requestId = "reuse-" + n;
              JsonNode rack = cluster.invoke(healthRequest(requestId));
              assertEquals(200, rack.path("status").asInt(), rack.toString());

              JsonNode body = SupervisorMain.JSON.readTree(rack.path("body").asText());
              assertTrue(body.path("ok").asBoolean(), body.toString());
              assertEquals("truffleruby-graal-lambda", body.path("runtime").asText());
              assertEquals("lambda", body.path("execution_mode").asText());
              assertEquals(requestId, body.path("request_id").asText());
              returnedRequestIds.add(body.path("request_id").asText());
              return rack;
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }, clients));
        }
        for (var call : calls) {
          call.get();
        }
      } finally {
        clients.shutdownNow();
      }

      assertEquals(250, returnedRequestIds.size());
      assertEquals(contextIdentity, cell.contextIdentity());
      assertTrue(cell.hostThreadCount() > 1, "expected the same context to be entered by multiple host threads");
      assertTrue(cell.hostThreadCount() <= 5, "host concurrency must stay bounded by the isolate pool");
    }
  }

  @Test
  void multipleIsolatesRemainIndependent() throws Exception {
    var settings = SupervisorMain.Settings.test(appRoot(), 2, 3);
    try (var cluster = new SupervisorMain.Cluster(settings)) {
      assertEquals(2, cluster.cellCount());
      assertEquals(6, cluster.workerCount());
      assertNotEquals(cluster.cells.get(0).contextIdentity(), cluster.cells.get(1).contextIdentity());
    }
  }

  @Test
  void retiringCellIsReplacedBeforeDrainAndForceClosedAtDeadline() throws Exception {
    Path root = appRoot();
    var settings = new SupervisorMain.Settings(
      "127.0.0.1",
      0,
      root,
      "routes/healthz/_get/handler.rb",
      "http://127.0.0.1:9/v1",
      "",
      1,
      2,
      2,
      1_800_000,
      300_000,
      25,
      15_000
    );

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      var retiring = cluster.cells.get(0);
      int oldContext = retiring.contextIdentity();
      retiring.active.incrementAndGet();

      long retiredAt = System.currentTimeMillis();
      retiring.retire(retiredAt);
      cluster.maintain(retiredAt);

      assertEquals(1, cluster.acceptingCellCount());
      assertEquals(2, cluster.cellCount(), "replacement should accept traffic while old context drains");
      var replacement = cluster.cells.stream().filter(SupervisorMain.Cell::accepting).findFirst().orElseThrow();
      assertNotEquals(oldContext, replacement.contextIdentity());

      cluster.maintain(retiredAt + settings.drainMs + 1);
      assertTrue(retiring.closed, "hung retiring context must be force-closed at drain deadline");
      assertEquals(1, cluster.cellCount());
      assertEquals(1, cluster.acceptingCellCount());
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
      () -> SupervisorMain.HttpBridge.targetUri(base, "GET", "//evil.example/path", "")
    );
  }

  @Test
  void graalUnitCannotEscapeGeneratedSourceRoot() {
    Path root = appRoot().resolve("generated/graal").toAbsolutePath().normalize();
    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.Settings.resolveUnit(root, "../lambda/entrypoint.rb")
    );
    assertThrows(
      IllegalArgumentException.class,
      () -> SupervisorMain.Settings.resolveUnit(root, "/tmp/escape.rb")
    );
  }

  @Test
  void settingsRequireGeneratedGraalSourcesNotRailsBootFiles(@TempDir Path temp) throws Exception {
    Files.createDirectories(temp.resolve("generated/graal/routes/healthz/_get"));
    Files.writeString(temp.resolve("generated/graal/common.rb"), "# generated common\n");
    Files.writeString(
      temp.resolve("generated/graal/routes/healthz/_get/handler.rb"),
      "# generated unit\n"
    );

    var settings = new SupervisorMain.Settings(
      "127.0.0.1",
      0,
      temp,
      "routes/healthz/_get/handler.rb",
      "http://127.0.0.1:9/v1",
      "",
      1,
      1,
      1,
      1_800_000,
      300_000,
      5_000,
      15_000
    );

    assertFalse(Files.exists(temp.resolve("config/environment.rb")));
    assertEquals(temp.toAbsolutePath().normalize(), settings.appRoot);
    assertTrue(settings.commonSourcePath.startsWith(temp.resolve("generated/graal").toAbsolutePath().normalize()));
    assertTrue(settings.unitSourcePath.startsWith(temp.resolve("generated/graal").toAbsolutePath().normalize()));
  }

  private static Path appRoot() {
    return Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
  }

  private static ObjectNode healthRequest(String requestId) {
    ObjectNode req = SupervisorMain.JSON.createObjectNode();
    req.put("request_id", requestId);
    req.put("method", "GET");
    req.put("path", "/healthz");
    req.put("query_string", "");
    req.put("body", "");
    req.put("content_type", "application/json");
    return req;
  }
}
