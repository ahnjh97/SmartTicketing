package smartticketing.booking;

import java.nio.file.Path;
import java.util.*;

/** Fresh fixture worker per scenario/mode; BenchmarkBackend owns a separate production server. */
final class BenchmarkProcess {
    static String cacheMode() { return System.getenv().getOrDefault("BENCH_CACHE_MODE","compare"); }
    static List<Boolean> modes(boolean smoke, boolean login) {
        return switch(cacheMode()) {
            case "on" -> List.of(true);
            case "off" -> List.of(false);
            case "compare" -> login?List.of(false):smoke?List.of(false,true):List.of(false,true,true,false);
            default -> throw new IllegalArgumentException("Invalid cache mode: "+cacheMode());
        };
    }
    static void run(Class<?> main, Map<String,String> variables) throws Exception {
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xmx1536m","-cp",System.getProperty("java.class.path"),main.getName());
        var process=new ProcessBuilder(command).inheritIO();
        process.environment().putAll(variables);
        process.environment().put("BENCH_CHILD","true");
        var child=process.start();
        Thread cleanup=new Thread(()->{ child.descendants().forEach(ProcessHandle::destroyForcibly); child.destroyForcibly(); });
        Runtime.getRuntime().addShutdownHook(cleanup);
        int code;
        try { code=child.waitFor(); } finally { Runtime.getRuntime().removeShutdownHook(cleanup); }
        if(code!=0) throw new IllegalStateException("Benchmark child failed: "+variables+", exit="+code);
    }
}
