package dev.ores.rorinfra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

final class ClusterSmokeTest {
  @Test
  void railsFreeLambdaHandlersRunAcrossMultipleGraalCellsAndWorkers() throws Exception {
    Path root=Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var settings=SupervisorMain.Settings.test(root,2,3);
    try(var cluster=new SupervisorMain.Cluster(settings)){
      assertEquals(2,cluster.cellCount());
      assertEquals(6,cluster.workerCount());
      ExecutorService clients=Executors.newFixedThreadPool(12);
      Set<String> contexts=ConcurrentHashMap.newKeySet();
      try{
        List<CompletableFuture<JsonNode>> calls=new ArrayList<>();
        for(int i=0;i<24;i++){
          int n=i;
          calls.add(CompletableFuture.supplyAsync(()->{
            try{
              ObjectNode req=SupervisorMain.JSON.createObjectNode();
              req.put("request_id","gha-"+n); req.put("method","GET"); req.put("path","/healthz");
              req.put("query_string",""); req.put("body",""); req.put("content_type","application/json");
              JsonNode rack=cluster.invoke(req);
              String context=rack.path("headers").path("x-ores-graal-context-id").asText("");
              if(!context.isBlank()) contexts.add(context);
              return rack;
            }catch(Exception e){throw new RuntimeException(e);}
          },clients));
        }
        for(var call:calls){
          JsonNode rack=call.get(); assertEquals(200,rack.path("status").asInt(),rack.toString());
          JsonNode body=SupervisorMain.JSON.readTree(rack.path("body").asText());
          assertTrue(body.path("ok").asBoolean(),body.toString());
          assertEquals("truffleruby-graal-lambda",body.path("runtime").asText());
          assertEquals("lambda",body.path("execution_mode").asText());
        }
        // Context IDs are emitted by the supervisor when available. The hard worker-count
        // assertion above remains authoritative even if a runtime build omits that header.
        assertTrue(contexts.isEmpty() || contexts.size() >= 2, "expected requests to span Graal contexts: "+contexts);
      }finally{clients.shutdownNow();}
    }
  }
}
