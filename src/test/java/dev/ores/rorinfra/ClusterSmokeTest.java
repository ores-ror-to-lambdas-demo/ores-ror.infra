package dev.ores.rorinfra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

final class ClusterSmokeTest {
  @Test
  void sameRailsAppRunsAcrossMultipleGraalCellsAndWorkers() throws Exception {
    Path root=Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var settings=SupervisorMain.Settings.test(root,2,3);
    try(var cluster=new SupervisorMain.Cluster(settings)){
      assertEquals(2,cluster.cellCount());
      assertEquals(6,cluster.workerCount());
      ExecutorService clients=Executors.newFixedThreadPool(12);
      try{
        List<CompletableFuture<JsonNode>> calls=new ArrayList<>();
        for(int i=0;i<24;i++){
          int n=i;
          calls.add(CompletableFuture.supplyAsync(()->{
            try{
              ObjectNode req=SupervisorMain.JSON.createObjectNode();
              req.put("request_id","gha-"+n); req.put("method","GET"); req.put("path","/healthz");
              req.put("query_string",""); req.put("body",""); req.put("content_type","application/json");
              return cluster.invoke(req);
            }catch(Exception e){throw new RuntimeException(e);}
          },clients));
        }
        for(var call:calls){
          JsonNode rack=call.get(); assertEquals(200,rack.path("status").asInt());
          JsonNode body=SupervisorMain.JSON.readTree(rack.path("body").asText());
          assertTrue(body.path("ok").asBoolean());
          assertEquals("truffleruby-graal",body.path("runtime").asText());
        }
      }finally{clients.shutdownNow();}
    }
  }
}
