package smartticketing.booking;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import smartticketing.auth.JwtService;
import smartticketing.config.JwtConfig;
import org.springframework.core.env.StandardEnvironment;

/** File RPC to the host controller: the fixture container never receives the Docker socket. */
final class BenchmarkBackend implements AutoCloseable {
    static final String SECRET="local-load-only-not-production-secret-2026-123456789";
    static final String BASE_URL="http://backend:8080";
    final String containerId;
    private final Path evidence;
    BenchmarkBackend(List<String> properties, Path evidence) throws Exception {
        this.evidence=evidence;
        var result=request(Map.of("action","start","args",properties,"evidence",evidence.toString()));
        containerId=result.get("containerId").toString();
    }
    static JwtService jwt() {
        var config=new JwtConfig();
        return new JwtService(config.jwtEncoder(config.jwtSecretKey(SECRET)),new StandardEnvironment());
    }
    static int k6(List<String> args, Map<String,String> env, Path log) throws Exception {
        String duration=env.getOrDefault("REDIS_BENCH_DURATION",env.getOrDefault("DURATION","0s"));
        long seconds=Long.parseLong(duration.substring(0,duration.length()-1))*(duration.endsWith("m")?60:1);
        var result=request(Map.of("action","k6","args",args,"env",env,"log",log.toString(),"waitSeconds",seconds+900));
        return ((Number)result.get("exitCode")).intValue();
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> request(Map<String,Object> data) throws Exception {
        Path control=Path.of("/results/control"); Files.createDirectories(control);
        String id=UUID.randomUUID().toString();
        Path pending=control.resolve(id+".tmp"),request=control.resolve(id+".request.json"),response=control.resolve(id+".response.json");
        Files.writeString(pending,QueryRedisBenchmark.JSON.writeValueAsString(data));
        Files.move(pending,request,StandardCopyOption.ATOMIC_MOVE);
        long deadline=System.nanoTime()+Duration.ofSeconds(((Number)data.getOrDefault("waitSeconds",900)).longValue()).toNanos();
        while(!Files.exists(response)) {
            if(System.nanoTime()>deadline) throw new IllegalStateException("Container controller timed out: "+data.get("action"));
            Thread.sleep(100);
        }
        var result=(Map<String,Object>)QueryRedisBenchmark.JSON.readValue(Files.readString(response),Map.class);
        Files.delete(response);
        if(result.containsKey("error")) throw new IllegalStateException(result.get("error").toString());
        return result;
    }
    @Override public void close() throws Exception { request(Map.of("action","stop","evidence",evidence.toString())); }
}
