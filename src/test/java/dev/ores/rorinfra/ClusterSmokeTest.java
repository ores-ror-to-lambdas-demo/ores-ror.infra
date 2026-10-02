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
  void routeGranularityUsesOneLongLivedContextPerRouteWithBoundedReusableThreads() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var settings = SupervisorMain.Settings.test(root, "route", 3);

    try (var cluster = new SupervisorMain.Cluster(settings)) {
      assertEquals(settings.unitCount(), cluster.contextCount());
      assertEquals(settings.unitCount() * 3, cluster.workerCount());

      ExecutorService clients = Executors.newFixedThreadPool(18);
      Set<String> contextIds = ConcurrentHashMap.newKeySet();
      Set<String> workerThreads = ConcurrentHashMap.newKeySet();
      try {
        List<CompletableFuture<JsonNode>> calls = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
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
          assertEquals("truffleruby-graal", body.path("runtime").asText());
          assertEquals("graal", body.path("execution_mode").asText());
          contextIds.add(response.path("headers").path("x-ores-graal-context-id").asText());
          workerThreads.add(response.path("headers").path("x-ores-graal-worker-thread").asText());
          assertTrue(response.path("headers").path("x-ores-graal-isolate-key").asText().startsWith("route:"));
        }
        assertEquals(1, contextIds.size(), "one route must keep one Context across request volume");
        assertTrue(workerThreads.size() >= 2, "one Context should be entered by multiple reusable host workers");
        assertTrue(workerThreads.size() <= 3, "per-context workers must remain bounded");
        assertEquals(settings.unitCount(), cluster.contextCount(), "request volume must not create per-request Contexts");
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
  void guestNativeExtensionsRemainDisabled() throws Exception {
    Path root = Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    try (var cluster = new SupervisorMain.Cluster(SupervisorMain.Settings.test(root, "route", 1))) {
      var worker = cluster.cells.values().iterator().next();
      String result = worker.context.eval(
        "ruby",
        "begin; require 'fiddle'; 'loaded'; rescue LoadError, SecurityError => e; e.class.name; end"
      ).asString();
      assertNotEquals("loaded", result, "guest C/native extension loading must remain disabled");
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
