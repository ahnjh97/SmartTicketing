package smartticketing.admission;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.cors.CorsConfiguration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("core")
class AdmissionFilterTests {
    @Test void decodedServletPathIsCheckedAndMalformedCookiesCannotReachApplication() throws Exception {
        var encoded=new MockHttpServletRequest("GET","/%61pi/movies");encoded.setServletPath("/api/movies");
        var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
        filter.doFilter(encoded,response,chain);
        assertThat(response.getStatus()).isEqualTo(429);verifyNoInteractions(chain,store);
        for(String token:List.of("forged","../active","A".repeat(200))) {
            var request=new MockHttpServletRequest("GET","/api/movies");request.setCookies(new Cookie(AdmissionFilter.COOKIE,token));
            response=new MockHttpServletResponse();filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(429);
        }
        verifyNoInteractions(chain,store);
    }
    @Test void redisFailureOnProtectedApiFailsClosedAndNextSuccessfulCheckRecovers() throws Exception {
        String id=java.util.UUID.randomUUID().toString();
        when(store.execute("check",id)).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
                .thenReturn(new AdmissionStore.State("ADMITTED",0,30));
        var request=new MockHttpServletRequest("GET","/api/movies");request.setCookies(new Cookie(AdmissionFilter.COOKIE,id));
        var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
        filter.doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("10");verifyNoInteractions(chain);
        filter.doFilter(request,new MockHttpServletResponse(),chain);verify(chain).doFilter(eq(request),any());
    }
    @Test void disabledGateBypassesRedisAndDeniedOriginsCannotRegister() throws Exception {
        var disabled=new AdmissionFilter(store,new AdmissionSettings(false,100,10,600,90,20000,false),r -> null);
        var request=new MockHttpServletRequest("GET","/api/movies");var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
        disabled.doFilter(request,response,chain);verify(chain).doFilter(request,response);verifyNoInteractions(store);
        request=new MockHttpServletRequest("POST","/api/admission/enter");request.addHeader("Origin","https://untrusted.example");
        response=new MockHttpServletResponse();filter.doFilter(request,response,mock(FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(403);verifyNoInteractions(store);
    }
    final AdmissionStore store = mock(AdmissionStore.class);
    final AdmissionSettings settings = new AdmissionSettings(true,100,10,600,90,20000,true);
    final AdmissionFilter filter = new AdmissionFilter(store,settings,request -> {
        var config = new CorsConfiguration(); config.setAllowedOrigins(List.of("http://localhost:5173"));
        config.setAllowedMethods(List.of("GET","POST","OPTIONS")); config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true); return config;
    });
    @Test void blocksAllDynamicEntryPathsBeforeApplication() throws Exception {
        for (String path : List.of("/api/movies","/api/auth/login","/api/tickets/verify/a","/oauth2/authorization/google","/login/oauth2/code/google")) {
            var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
            filter.doFilter(new MockHttpServletRequest("GET",path),response,chain);
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getContentAsString()).contains("ADMISSION_REQUIRED"); verifyNoInteractions(chain);
        }
    }
    @Test void admissionIsNotAuthenticationAndDownstreamErrorsAreNotMasked() throws Exception {
        String id=java.util.UUID.randomUUID().toString();
        when(store.execute("check",id)).thenReturn(new AdmissionStore.State("ADMITTED",0,30));
        var request = new MockHttpServletRequest("GET","/api/tickets"); request.setCookies(new Cookie(AdmissionFilter.COOKIE,id));
        var response = new MockHttpServletResponse(); var chain=mock(FilterChain.class);
        doThrow(new IllegalStateException("application failure")).when(chain).doFilter(request,response);
        assertThatThrownBy(() -> filter.doFilter(request,response,chain)).isInstanceOf(IllegalStateException.class).hasMessage("application failure");
    }
    @Test void redisFailureClosesEntryAndRegistrationSetsProtectedCookie() throws Exception {
        when(store.execute(eq("enter"),anyString())).thenReturn(new AdmissionStore.State("WAITING",3,5));
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/admission/enter"),response,mock(FilterChain.class));
        assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly","Secure","SameSite=Lax");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        when(store.execute(eq("enter"),anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/admission/enter"),response,mock(FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(503);
    }
    @Test void corsAndReadinessRemainAvailable() throws Exception {
        var request = new MockHttpServletRequest("OPTIONS","/api/admission/enter");
        request.addHeader("Origin","http://localhost:5173"); request.addHeader("Access-Control-Request-Method","POST");
        var response = new MockHttpServletResponse();
        filter.doFilter(request,response,mock(FilterChain.class));
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        var chain = mock(FilterChain.class);
        request = new MockHttpServletRequest("GET","/api/health/readiness");
        filter.doFilter(request,response,chain); verify(chain).doFilter(request,response); verifyNoInteractions(store);
    }
}
