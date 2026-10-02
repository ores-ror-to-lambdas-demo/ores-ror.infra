package dev.ores.rorinfra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  void routeGranularityUsesOneLongLivedContextWithBoundedReusableHostThreads() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var settings = SupervisorMain.Settings.test(root, "route", 3);

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      assertEquals(0, cluster.contextCount());
      assertEquals(0, cluster.workerCount());
      assertEquals(0, cluster.admissionCapacity());

      ExecutorService clients = Executors.newFixedThreadPool(3);
      Set<String> contextIds = ConcurrentHashMap.newKeySet();
      Set<String> workerThreads = ConcurrentHashMap.newKeySet();
      try {
        List<CompletableFuture<JsonNode>> calls = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
          int n = i;
          calls.add(CompletableFuture.supplyAsync(() -> {
            try {
              return cluster.invoke(request("gha-" + n, "GET", "/healthz"));
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          }, clients));
        }
        for (var call : calls) {
          JsonNode response = call.get();
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
        assertEquals(1, workerThreads.size(), "each Context must remain pinned to one guest-owner thread");
        assertEquals(1, cluster.contextCount(), "one requested route should own one long-lived Context");
        assertEquals(1, cluster.workerCount());
        assertEquals(3, cluster.admissionCapacity());
        var healthRoute = settings.resolveRoute("GET", "/healthz");
        var health = cluster.cells.get(settings.unitKey(healthRoute));
        assertEquals(1, health.maxConcurrentGuestEntries(), "TruffleRuby Context entry must be serialized");
      } finally {
        clients.shutdownNow();
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
  void guestDangerousHostCapabilitiesRemainBlocked() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    try (var cluster = new SupervisorMain.Cluster(SupervisorMain.Settings.test(root, "route", 1))) {
      JsonNode health = cluster.invoke(request("capabilities", "GET", "/healthz"));
      assertEquals(200, health.path("status").asInt());
      var healthRoute = cluster.settings.resolveRoute("GET", "/healthz");
      var worker = cluster.cells.get(cluster.settings.unitKey(healthRoute));

      String file = worker.evalForTest("begin; File.read('/etc/passwd'); 'allowed'; rescue Exception => e; e.class.name; end"
      );
      assertNotEquals("allowed", file, "guest filesystem access must remain blocked");

      String process = worker.evalForTest("begin; system('true'); 'allowed'; rescue Exception => e; e.class.name; end"
      );
      assertNotEquals("allowed", process, "guest process creation must remain blocked");

      String thread = worker.evalForTest("begin; Thread.new { 1 }.join; 'allowed'; rescue Exception => e; e.class.name; end"
      );
      assertNotEquals("allowed", thread, "guest-created threads must remain blocked");

      String hostClass = worker.evalForTest("begin; Java.type('java.lang.System'); 'allowed'; rescue Exception => e; e.class.name; end"
      );
      assertNotEquals("allowed", hostClass, "host class lookup must remain blocked");
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
