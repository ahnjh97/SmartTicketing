package smartticketing.benchmark;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.*;
import java.sql.*;
import java.util.Locale;

/** Compiled only with -PbenchmarkInstrumentation; never part of a normal deployment. */
@Configuration(proxyBeanMethods = false)
@Profile("benchmark-metrics")
public class RequestJdbcMetrics {
    static final ThreadLocal<Counts> CURRENT = new ThreadLocal<>();
    static final class Counts {
        long statements, rows, rankStatements, inventoryRows;
    }

    @Bean static BeanPostProcessor measuredDataSource() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource ds ? wrap(ds, DataSource.class, null) : bean;
            }
        };
    }

    @Bean RequestFilter requestJdbcMetricsFilter() { return new RequestFilter(); }

    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    static final class RequestFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            // Only the synchronous JSON benchmark endpoints are buffered (never SSE/downloads).
            String path = request.getRequestURI();
            if (!(path.startsWith("/api/showtimes") || path.startsWith("/api/booking-groups/")
                    || path.startsWith("/api/reservations/"))) {
                chain.doFilter(request, response); return;
            }
            var measured = new ContentCachingResponseWrapper(response);
            var counts = new Counts(); CURRENT.set(counts);
            try {
                chain.doFilter(request, measured);
                measured.setHeader("X-Bench-Instrumentation", "jdbc-request-v1");
                measured.setHeader("X-Bench-Sql", Long.toString(counts.statements));
                measured.setHeader("X-Bench-Rows", Long.toString(counts.rows));
                measured.setHeader("X-Bench-Rank-Sql", Long.toString(counts.rankStatements));
                measured.setHeader("X-Bench-Inventory-Rows", Long.toString(counts.inventoryRows));
                measured.copyBodyToResponse();
            } finally { CURRENT.remove(); }
        }
    }

    static boolean rankQuery(String sql) {
        if (sql == null) return false;
        String s = sql.toLowerCase(Locale.ROOT);
        // Both historical entity loading and current grouped COUNT compare queue numbers.
        return s.contains("waiting_queues") && s.contains("coalesce(")
                && s.contains("zone_queue_number") && s.contains("<") && s.stripLeading().startsWith("select");
    }

    @SuppressWarnings("unchecked")
    static <T> T wrap(T delegate, Class<T> api, String preparedSql) {
        return (T) Proxy.newProxyInstance(RequestJdbcMetrics.class.getClassLoader(), new Class<?>[]{api},
                new InvocationHandler() {
            String executedSql = preparedSql;
            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name.equals("unwrap") && ((Class<?>) args[0]).isInstance(proxy)) return proxy;
                if (name.equals("isWrapperFor") && ((Class<?>) args[0]).isInstance(proxy)) return true;
                Counts counts = CURRENT.get();
                if (delegate instanceof Statement && (name.equals("execute") || name.equals("executeQuery")
                        || name.equals("executeUpdate") || name.equals("executeLargeUpdate")
                        || name.equals("executeBatch") || name.equals("executeLargeBatch"))) {
                    executedSql = args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
                    if (counts != null) {
                        counts.statements++;
                        if (rankQuery(executedSql)) counts.rankStatements++;
                    }
                }
                Object result;
                try { result = method.invoke(delegate, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
                if (delegate instanceof ResultSet && name.equals("next") && Boolean.TRUE.equals(result) && counts != null) {
                    counts.rows++;
                    if (preparedSql != null && preparedSql.toLowerCase(Locale.ROOT).contains("showtime_seats")) counts.inventoryRows++;
                }
                if (result instanceof Connection c) return wrap(c, Connection.class, null);
                String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : executedSql;
                if (result instanceof CallableStatement s) return wrap(s, CallableStatement.class, sql);
                if (result instanceof PreparedStatement s) return wrap(s, PreparedStatement.class, sql);
                if (result instanceof Statement s) return wrap(s, Statement.class, null);
                if (result instanceof ResultSet rs) return wrap(rs, ResultSet.class, executedSql);
                return result;
            }
        });
    }
}
