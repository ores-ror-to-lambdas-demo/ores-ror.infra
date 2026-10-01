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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

public final class SupervisorMain {
  static final ObjectMapper JSON = new ObjectMapper();
  static final int MAX_BODY = 1024 * 1024;

  private SupervisorMain() {}

  public static void main(String[] args) throws Exception {
    Settings s = Settings.fromEnv();
    try (Cluster cluster = new Cluster(s)) {
      ExecutorService ingress = Executors.newFixedThreadPool(
        Math.max(4, s.maxCells * s.workersPerCell), named("ingress"));
      HttpServer server = HttpServer.create(new InetSocketAddress(s.bindHost, s.port), 128);
      server.setExecutor(ingress);
      server.createContext("/", ex -> handle(ex, cluster));
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        server.stop(1); ingress.shutdown(); cluster.close();
      }, "ror-shutdown"));
      server.start();
      System.out.printf("Rails/Graal cluster listening on http://%s:%d cells=%d workers/cell=%d%n",
        s.bindHost, s.port, s.minCells, s.workersPerCell);
      Thread.currentThread().join();
    }
  }

  static void handle(HttpExchange ex, Cluster cluster) throws IOException {
    try {
      byte[] input = ex.getRequestBody().readNBytes(MAX_BODY + 1);
      if (input.length > MAX_BODY) { sendError(ex, 413, "request body too large"); return; }
      ObjectNode req = JSON.createObjectNode();
      req.put("request_id", requestId(ex.getRequestHeaders().getFirst("x-request-id")));
      req.put("method", ex.getRequestMethod());
      req.put("path", ex.getRequestURI().getPath());
      req.put("query_string", ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery());
      req.put("body", new String(input, StandardCharsets.UTF_8));
      req.put("content_type", valueOr(ex.getRequestHeaders().getFirst("content-type"), "application/json"));
      JsonNode result = cluster.invoke(req);
      int status = result.path("status").asInt(500);
      result.path("headers").fields().forEachRemaining(h -> {
        if (h.getValue().isTextual() && !h.getKey().equalsIgnoreCase("content-length")
            && !h.getKey().equalsIgnoreCase("connection")) {
          ex.getResponseHeaders().set(h.getKey(), h.getValue().asText());
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
      sendError(ex, 500, safe(e));
    } finally { ex.close(); }
  }

  static void sendError(HttpExchange ex, int status, String message) throws IOException {
    byte[] b = JSON.writeValueAsBytes(JSON.createObjectNode().put("error", message));
    ex.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(status, b.length); ex.getResponseBody().write(b);
  }

  static final class Settings {
    final String bindHost; final int port; final Path appRoot; final Path bootstrap;
    final String railsEnv; final String dataUrl; final String dataToken;
    final int minCells; final int maxCells; final int workersPerCell;
    final long maxAgeMs; final long idleMs; final long drainMs;

    Settings(String bindHost, int port, Path appRoot, String railsEnv, String dataUrl,
             String dataToken, int minCells, int maxCells, int workersPerCell,
             long maxAgeMs, long idleMs, long drainMs) {
      this.bindHost=bindHost; this.port=port; this.appRoot=appRoot;
      this.bootstrap=appRoot.resolve("graal/bootstrap.rb").normalize(); this.railsEnv=railsEnv;
      this.dataUrl=dataUrl; this.dataToken=dataToken; this.minCells=minCells; this.maxCells=maxCells;
      this.workersPerCell=workersPerCell; this.maxAgeMs=maxAgeMs; this.idleMs=idleMs; this.drainMs=drainMs;
      validate();
    }

    static Settings fromEnv() {
      int min = integer("MIN_ISOLATES", 1, 1, 32);
      return new Settings(env("BIND_HOST","127.0.0.1"), integer("PORT",8080,1,65535),
        Path.of(env("APP_ROOT","../ores-ror.rb")).toAbsolutePath().normalize(), env("RAILS_ENV","production"),
        env("DATA_API_URL","http://127.0.0.1:8787/v1"), env("DATA_API_TOKEN",""), min,
        integer("MAX_ISOLATES",4,min,64), integer("ISOLATE_MAX_CONCURRENCY",5,1,5),
        1000L*integer("ISOLATE_MAX_AGE_SECONDS",1800,60,1800),
        1000L*integer("ISOLATE_IDLE_SECONDS",300,30,300),
        1000L*integer("ISOLATE_DRAIN_SECONDS",30,1,300));
    }

    static Settings test(Path root, int cells, int workers) {
      return new Settings("127.0.0.1",0,root.toAbsolutePath().normalize(),"test",
        "http://127.0.0.1:9/v1","",cells,cells,workers,1_800_000,300_000,5_000);
    }

    void validate() {
      if (!Files.isRegularFile(appRoot.resolve("config/environment.rb")))
        throw new IllegalArgumentException("APP_ROOT is not a Rails app: "+appRoot);
      if (!Files.isRegularFile(bootstrap))
        throw new IllegalArgumentException("missing graal/bootstrap.rb: "+bootstrap);
      URI u=URI.create(dataUrl); if (!List.of("http","https").contains(u.getScheme()))
        throw new IllegalArgumentException("DATA_API_URL must use http or https");
    }

    static int integer(String n,int d,int min,int max) {
      int v=Integer.parseInt(env(n,Integer.toString(d)));
      if(v<min||v>max) throw new IllegalArgumentException(n+" must be between "+min+" and "+max); return v;
    }
    static String env(String n,String d) { String v=System.getenv(n); return v==null||v.isBlank()?d:v; }
  }

  static final class Cluster implements AutoCloseable {
    final Settings s; final HttpBridge http; final List<Cell> cells=new ArrayList<>();
    final ScheduledExecutorService maintenance=Executors.newSingleThreadScheduledExecutor(named("maintenance"));
    long nextId; boolean closed;

    Cluster(Settings s) throws Exception {
      this.s=s; this.http=new HttpBridge(s.dataUrl,s.dataToken);
      synchronized(this){ ensureMin(); }
      maintenance.scheduleAtFixedRate(this::maintainSafe,1,1,TimeUnit.SECONDS);
    }

    JsonNode invoke(ObjectNode req) throws Exception {
      Cell cell; synchronized(this){ maintain(System.currentTimeMillis()); cell=select(); }
      return cell.submit(req).get(15,TimeUnit.SECONDS);
    }

    synchronized int cellCount(){ return cells.size(); }
    synchronized int workerCount(){ return cells.stream().mapToInt(c->c.s.workersPerCell).sum(); }

    Cell select() throws Exception {
      List<Cell> open=cells.stream().filter(Cell::accepting).sorted(Comparator.comparingInt(Cell::load)).toList();
      if(open.isEmpty()){ Cell c=create(); cells.add(c); return c; }
      Cell c=open.get(0);
      if(c.load()>=s.workersPerCell && open.size()<s.maxCells){ Cell n=create(); cells.add(n); return n; }
      return c;
    }

    void maintainSafe(){ try{ synchronized(this){ if(!closed) maintain(System.currentTimeMillis()); } }catch(Throwable e){System.err.println(safe(e));} }
    void maintain(long now) throws Exception {
      int idleBudget=Math.max(0,(int)cells.stream().filter(Cell::accepting).count()-s.minCells);
      for(Cell c:cells){ if(!c.accepting()) continue; if(c.old(now)) c.retire(); else if(idleBudget>0&&c.idle(now)){c.retire();idleBudget--;} }
      ensureMin();
      List<Cell> gone=new ArrayList<>(); for(Cell c:cells){if(c.retiring()&&c.load()==0){c.close();gone.add(c);}} cells.removeAll(gone); ensureMin();
    }
    void ensureMin() throws Exception { while(!closed&&cells.stream().filter(Cell::accepting).count()<s.minCells) cells.add(create()); }
    Cell create() throws Exception { return new Cell("cell-"+(++nextId),s,http); }
    public synchronized void close(){ if(closed)return; closed=true; maintenance.shutdownNow(); for(Cell c:cells)c.closeAfterDrain(); cells.clear(); }
  }

  static final class Cell implements AutoCloseable {
    final String id; final Settings s; final Engine engine; final Context context; final Value invoke;
    final ThreadPoolExecutor pool; final Set<String> hostThreads=ConcurrentHashMap.newKeySet();
    final long born=System.currentTimeMillis(); final AtomicLong last=new AtomicLong(born);
    final AtomicInteger active=new AtomicInteger(); volatile boolean open=true; volatile boolean closed;

    Cell(String id,Settings s,HttpBridge http) throws Exception {
      this.id=id; this.s=s; this.engine=Engine.newBuilder("ruby").build();
      IOAccess io=IOAccess.newBuilder().allowHostFileAccess(true).allowHostSocketAccess(false).build();
      Context candidate=Context.newBuilder("ruby").engine(engine).allowAllAccess(false).allowHostAccess(HostAccess.EXPLICIT)
        .allowHostClassLookup(n->false).allowHostClassLoading(false).allowNativeAccess(false).allowCreateProcess(false)
        .allowCreateThread(false).allowEnvironmentAccess(EnvironmentAccess.NONE).allowIO(io).allowPolyglotAccess(PolyglotAccess.NONE)
        .currentWorkingDirectory(s.appRoot).build();
      Value candidateInvoke=null;
      try{
        Value b=candidate.getBindings("ruby");
        b.putMember("gs_http",(ProxyExecutable)http::call);
        b.putMember("gs_app_root",s.appRoot.toString());
        b.putMember("gs_rails_env",s.railsEnv);
        Source boot=Source.newBuilder("ruby",Files.readString(s.bootstrap,StandardCharsets.UTF_8),"graal/bootstrap.rb").interactive(true).cached(true).build();
        candidateInvoke=candidate.eval(boot);
        if(!candidateInvoke.canExecute()) throw new IllegalStateException("bootstrap did not return Rack invoker");
      }catch(Exception e){
        candidate.close(true);
        engine.close();
        throw e;
      }
      this.context=candidate;
      this.invoke=candidateInvoke;
      this.pool=new ThreadPoolExecutor(s.workersPerCell,s.workersPerCell,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(s.workersPerCell*8),named(id),new ThreadPoolExecutor.AbortPolicy());
    }

    CompletableFuture<JsonNode> submit(ObjectNode req){ if(!accepting())throw new RejectedExecutionException("cell retiring"); last.set(System.currentTimeMillis());
      CompletableFuture<JsonNode> f=new CompletableFuture<>(); pool.execute(()->{active.incrementAndGet();hostThreads.add(Thread.currentThread().getName());try{Value v=invoke.execute(JSON.writeValueAsString(req));f.complete(JSON.readTree(v.asString()));}catch(Throwable e){f.completeExceptionally(e);}finally{active.decrementAndGet();last.set(System.currentTimeMillis());}});return f; }
    int load(){return active.get()+pool.getQueue().size();} boolean accepting(){return open&&!closed;} boolean retiring(){return !open&&!closed;}
    boolean old(long n){return n-born>=s.maxAgeMs;} boolean idle(long n){return load()==0&&n-last.get()>=s.idleMs;} void retire(){open=false;pool.shutdown();}
    int contextIdentity(){return System.identityHashCode(context);} int hostThreadCount(){return hostThreads.size();}
    void closeAfterDrain(){open=false;pool.shutdown();try{if(!pool.awaitTermination(s.drainMs,TimeUnit.MILLISECONDS))pool.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();pool.shutdownNow();}closeResources();}
    public void close(){open=false;pool.shutdown();if(load()!=0)throw new IllegalStateException("cell busy");closeResources();}
    synchronized void closeResources(){if(closed)return;closed=true;context.close(true);engine.close();}
  }

  static final class HttpBridge {
    final URI base; final String token; final HttpClient client;
    HttpBridge(String url,String token){base=URI.create(url.replaceAll("/$","")+"/");this.token=token;client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();}
    Object call(Value...args){try{JsonNode r=JSON.readTree(args[0].asString());String method=r.path("method").asText("GET").toUpperCase();String path=r.path("path").asText();if(!path.startsWith("/")||path.contains("://"))throw new IllegalArgumentException("relative HTTP path required");String q=query(r.path("query"));URI u=URI.create(base.toString().replaceAll("/$","")+path+(q.isEmpty()?"":"?"+q));if(!sameOrigin(base,u))throw new IllegalArgumentException("HTTP origin escape");JsonNode bn=r.get("body");String body=bn==null||bn.isNull()?"":JSON.writeValueAsString(bn);HttpRequest.Builder b=HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(10)).header("accept","application/json").header("content-type","application/json");if(token!=null&&!token.isEmpty())b.header("authorization","Bearer "+token);b.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));HttpResponse<InputStream> resp=client.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(InputStream in=resp.body()){bytes=in.readNBytes(MAX_BODY+1);}if(bytes.length>MAX_BODY)throw new IllegalStateException("HTTP response too large");return JSON.writeValueAsString(JSON.createObjectNode().put("ok",true).put("status",resp.statusCode()).put("body",new String(bytes,StandardCharsets.UTF_8)));}catch(Exception e){try{return JSON.writeValueAsString(JSON.createObjectNode().put("ok",false).put("error",safe(e)));}catch(Exception x){return "{\"ok\":false,\"error\":\"bridge failure\"}";}}}
    static String query(JsonNode q){if(q==null||!q.isObject()||q.isEmpty())return "";List<String> p=new ArrayList<>();q.fields().forEachRemaining(e->p.add(URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue().asText(),StandardCharsets.UTF_8)));return String.join("&",p);}
    static boolean sameOrigin(URI a,URI b){return a.getScheme().equalsIgnoreCase(b.getScheme())&&a.getHost().equalsIgnoreCase(b.getHost())&&port(a)==port(b);} static int port(URI u){return u.getPort()>=0?u.getPort():"https".equalsIgnoreCase(u.getScheme())?443:80;}
  }

  static ThreadFactory named(String p){AtomicInteger n=new AtomicInteger();return r->new Thread(r,p+"-"+n.incrementAndGet());}
  static String requestId(String v){return v!=null&&v.matches("[A-Za-z0-9._:-]{1,128}")?v:"ores-request-"+UUID.randomUUID();}
  static String valueOr(String v,String d){return v==null||v.isBlank()?d:v;}
  static String safe(Throwable e){Throwable r=e.getCause()!=null?e.getCause():e;String s=r.getMessage()==null?r.getClass().getSimpleName():r.getMessage();return s.length()>512?s.substring(0,512):s;}
}
