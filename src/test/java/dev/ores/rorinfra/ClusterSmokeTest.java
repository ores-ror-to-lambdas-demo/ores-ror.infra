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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

final class ClusterSmokeTest {
  @Test
  void oneContextServesManyRequestsAcrossReusableHostThreads() throws Exception {
    Path root=Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var settings=SupervisorMain.Settings.test(root,1,5);
    try(var cluster=new SupervisorMain.Cluster(settings)){
      assertEquals(1,cluster.cellCount());
      assertEquals(5,cluster.workerCount());
      var cell=cluster.cells.get(0);
      int contextIdentity=cell.contextIdentity();

      ExecutorService clients=Executors.newFixedThreadPool(20);
      Set<String> returnedRequestIds=ConcurrentHashMap.newKeySet();
      try{
        List<CompletableFuture<JsonNode>> calls=new ArrayList<>();
        for(int i=0;i<250;i++){
          int n=i;
          calls.add(CompletableFuture.supplyAsync(()->{
            try{
              String requestId="reuse-"+n;
              ObjectNode req=SupervisorMain.JSON.createObjectNode();
              req.put("request_id",requestId);
              req.put("method","GET");
              req.put("path","/healthz");
              req.put("query_string","");
              req.put("body","");
              req.put("content_type","application/json");
              JsonNode rack=cluster.invoke(req);
              assertEquals(200,rack.path("status").asInt());
              JsonNode body=SupervisorMain.JSON.readTree(rack.path("body").asText());
              assertTrue(body.path("ok").asBoolean());
              assertEquals("truffleruby-graal",body.path("runtime").asText());
              assertEquals(requestId,body.path("request_id").asText());
              returnedRequestIds.add(body.path("request_id").asText());
              return rack;
            }catch(Exception e){throw new RuntimeException(e);}
          },clients));
        }
        for(var call:calls) call.get();
      }finally{
        clients.shutdownNow();
      }

      assertEquals(250,returnedRequestIds.size());
      assertEquals(contextIdentity,cell.contextIdentity());
      assertTrue(cell.hostThreadCount()>1,"expected the same context to be entered by multiple host threads");
      assertTrue(cell.hostThreadCount()<=5,"host concurrency must stay bounded by the isolate pool");
    }
  }

  @Test
  void multipleIsolatesRemainIndependent() throws Exception {
    Path root=Path.of(System.getProperty("app.root")).toAbsolutePath().normalize();
    var settings=SupervisorMain.Settings.test(root,2,3);
    try(var cluster=new SupervisorMain.Cluster(settings)){
      assertEquals(2,cluster.cellCount());
      assertEquals(6,cluster.workerCount());
      assertTrue(cluster.cells.get(0).contextIdentity()!=cluster.cells.get(1).contextIdentity());
    }
  }
}
