package smartticketing.admission;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Runs before Spring Security (-100), MVC, authentication and database access. */
@Component
@Order(-110)
public class AdmissionFilter extends OncePerRequestFilter {
    public static final String COOKIE = "st_admission";
    private final AdmissionStore store;
    private final AdmissionSettings settings;
    private final CorsConfigurationSource cors;
    private final ObjectMapper json = new ObjectMapper();
    public AdmissionFilter(AdmissionStore store, AdmissionSettings settings,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) { this.store = store; this.settings = settings; this.cors = cors; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Use the container's decoded, normalized route, just like MVC/Security.
        // Raw requestURI can hide /api behind percent escapes or dot segments.
        String path = request.getServletPath();
        if (request.getPathInfo() != null) path += request.getPathInfo();
        if (path.isEmpty()) {
            try { path = org.springframework.web.util.UriUtils.decode(
                    request.getRequestURI().substring(request.getContextPath().length()),java.nio.charset.StandardCharsets.UTF_8); }
            catch (IllegalArgumentException malformed) { write(response,400,Map.of("code","INVALID_PATH")); return; }
        }
        boolean admission = path.startsWith("/api/admission/");
        if (!admission && (!settings.enabled() || path.equals("/api/health/readiness")
                || !(path.startsWith("/api/") || path.startsWith("/oauth2/") || path.startsWith("/login/")))) {
            chain.doFilter(request,response); return;
        }
        if (!new DefaultCorsProcessor().processRequest(cors.getCorsConfiguration(request), request, response)
                || CorsUtils.isPreFlightRequest(request)) return;
        String id = cookie(request);
        try {
            if (admission) {
                String op;
                if (path.equals("/api/admission/enter") && request.getMethod().equals("POST")) op = "enter";
                else if (path.equals("/api/admission/status") && request.getMethod().equals("GET")) op = "status";
                else if (path.equals("/api/admission/leave") && request.getMethod().equals("POST")) op = "leave";
                else { write(response,404,Map.of("code","NOT_FOUND")); return; }
                if (id == null && op.equals("enter")) { id = UUID.randomUUID().toString(); setCookie(response,id,86400); }
                var state = store.execute(op, id == null ? "" : id);
                if (op.equals("leave")) setCookie(response,"",0);
                write(response, state.state().equals("FULL") ? 429 : 200, state);
                return;
            }
            if (id != null && store.execute("check",id).state().equals("ADMITTED")) {
                // Continue outside the Redis error handler so application errors retain their own status.
            } else {
                response.setHeader("Retry-After","5");
                write(response,429,Map.of("code","ADMISSION_REQUIRED","message","접속 대기실에서 입장 순서를 확인해주세요."));
                return;
            }
        } catch (org.springframework.dao.DataAccessException | IllegalStateException e) {
            response.setHeader("Retry-After","10");
            write(response,503,Map.of("code","ADMISSION_UNAVAILABLE","message","접속 안내를 잠시 연결하지 못했습니다. 잠시 후 다시 확인합니다."));
            return;
        }
        chain.doFilter(request,response);
    }
    private String cookie(HttpServletRequest request) {
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies())
            if (COOKIE.equals(cookie.getName()) && cookie.getValue().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return cookie.getValue();
        return null;
    }
    private void setCookie(HttpServletResponse response, String id, long seconds) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE,id).httpOnly(true).secure(settings.secureCookie())
                .sameSite("Lax").path("/").maxAge(Duration.ofSeconds(seconds)).build().toString());
    }
    private void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");
        response.getWriter().write(json.writeValueAsString(body));
    }
}
