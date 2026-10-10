package smartticketing.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import smartticketing.service.AdminMaintenanceGate;
import java.io.IOException;
import java.util.Set;

@Component
public class AdminMaintenanceFilter extends OncePerRequestFilter {
    private final AdminMaintenanceGate gate;
    public AdminMaintenanceFilter(AdminMaintenanceGate gate) { this.gate = gate; }
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {
        String path=r.getRequestURI();
        if (!path.startsWith("/api/")) return true;
        return Set.of("GET", "HEAD", "OPTIONS").contains(r.getMethod());
    }
    @Override protected void doFilterInternal(HttpServletRequest r, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!gate.enterWriteRequest()) {
            response.setStatus(503); response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", "5");
            response.getWriter().write("{\"message\":\"관리자 데이터 작업 중입니다. 잠시 후 다시 시도해주세요.\"}");
            return;
        }
        try { chain.doFilter(r, response); } finally { gate.leaveWriteRequest(); }
    }
}
