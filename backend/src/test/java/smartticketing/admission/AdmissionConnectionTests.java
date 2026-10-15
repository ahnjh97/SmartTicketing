package smartticketing.admission;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("core")
class AdmissionConnectionTests {
    @Test void unresponsiveRedisReturns503WithinABoundedTimeInsteadOfPassingTheRequest() throws Exception {
        var accepted=new AtomicReference<Socket>();
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var peer=Thread.startVirtualThread(() -> {
                try(var socket=server.accept()) {
                    accepted.set(socket);
                    socket.getInputStream().readNBytes(4096); // Accept TCP but never answer the Redis handshake.
                } catch(java.io.IOException ignored) { }
            });
            var settings=new AdmissionSettings(true,100,10,600,90,20000,false);
            try(var store=new AdmissionConfiguration().admissionStore(settings,"127.0.0.1",server.getLocalPort(),"","network-fault-test")) {
                var filter=new AdmissionFilter(store,settings,request -> null);
                var response=new MockHttpServletResponse();var chain=mock(jakarta.servlet.FilterChain.class);
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(4),() ->
                        filter.doFilter(new MockHttpServletRequest("POST","/api/admission/enter"),response,chain));
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getContentAsString()).contains("ADMISSION_UNAVAILABLE");
                verifyNoInteractions(chain);
            } finally {
                if(accepted.get()!=null) accepted.get().close();
                server.close();peer.join(2000);
            }
        }
    }
}
