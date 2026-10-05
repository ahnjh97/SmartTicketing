package smartticketing.controller;

import smartticketing.dto.ticket.TicketResponse;
import smartticketing.dto.ticket.TicketVerifyResponse;
import smartticketing.service.TicketService;
import smartticketing.util.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;
    private final CurrentUser current;

    public TicketController(TicketService s, CurrentUser c) {
        service = s;
        current = c;
    }

    @PostMapping("/verify/complete/{qrCode}")
    public ResponseEntity<TicketVerifyResponse> completeVerify(@PathVariable String qrCode) {
        return ResponseEntity.ok(service.useNow(qrCode));
    }

    @PostMapping("/verify/{qrCode}")
    public ResponseEntity<TicketVerifyResponse> verify(@PathVariable String qrCode) {
        return ResponseEntity.ok(service.verifyAndUse(qrCode));
    }

    @GetMapping("/verify/{qrCode}")
    public ResponseEntity<?> verifyStatus(
            @PathVariable String qrCode,
            @RequestHeader(value = "Accept", defaultValue = "application/json") String accept
    ) {
        if (accept.contains("text/html")) {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/html; charset=UTF-8")
                    .body(ticketVerifyHtml());
        }

        return ResponseEntity.ok(service.verifyStatus(qrCode));
    }

    private String ticketVerifyHtml() {
        return """
                <!doctype html>
                <html lang="ko">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Smart Ticketing - 티켓 사용</title>
                    <style>
                        * { box-sizing: border-box; }
                        body {
                            margin: 0;
                            min-height: 100vh;
                            display: grid;
                            place-items: center;
                            padding: 24px;
                            background: #f4f5f7;
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                            color: #171717;
                        }
                        .card {
                            width: min(420px, 100%);
                            padding: 40px 28px;
                            border-radius: 24px;
                            background: white;
                            box-shadow: 0 18px 50px rgba(0,0,0,.12);
                            text-align: center;
                        }
                        .icon {
                            width: 64px;
                            height: 64px;
                            margin: 0 auto 20px;
                            display: grid;
                            place-items: center;
                            border-radius: 50%;
                            background: #171717;
                            color: white;
                            font-size: 28px;
                            font-weight: 700;
                        }
                        h1 { margin: 0 0 12px; font-size: 24px; }
                        p { margin: 0; color: #666; line-height: 1.6; }
                        .loading {
                            width: 32px;
                            height: 32px;
                            margin: 24px auto 0;
                            border: 3px solid #ddd;
                            border-top-color: #171717;
                            border-radius: 50%;
                            animation: spin .8s linear infinite;
                        }
                        .success {
                            background: #e8f7ed;
                            color: #16833a;
                        }
                        .success h1 { color: #16833a; }
                        .error {
                            background: #fff0f0;
                            color: #c62828;
                        }
                        @keyframes spin { to { transform: rotate(360deg); } }
                    </style>
                </head>
                <body>
                    <main class="card" id="card">
                        <div class="icon" id="icon">T</div>
                        <h1 id="title">티켓 확인 중</h1>
                        <p id="message">처리중입니다...</p>
                        <div class="loading" id="loading"></div>
                    </main>
                    <script>
                        const url = window.location.href;
                        const card = document.getElementById("card");
                        const icon = document.getElementById("icon");
                        const title = document.getElementById("title");
                        const message = document.getElementById("message");
                        const loading = document.getElementById("loading");

                        function showSuccess() {
                            card.className = "card success";
                            icon.textContent = "✓";
                            title.textContent = "티켓 사용 완료";
                            message.textContent = "티켓 사용처리가 되었습니다.";
                            loading.remove();
                        }

                        function showError(text) {
                            card.className = "card error";
                            icon.textContent = "!";
                            title.textContent = "티켓 사용 실패";
                            message.textContent = text || "티켓 사용 처리에 실패했습니다.";
                            loading.remove();
                        }

                        async function start() {
                            try {
                                const started = await fetch(url, {
                                    method: "POST",
                                    headers: { "Accept": "application/json" }
                                }).then(r => r.json());

                                if (started.used) {
                                    showSuccess();
                                    return;
                                }

                                if (!started.processing) {
                                    showError(started.message);
                                    return;
                                }

                                const poll = async () => {
                                    const result = await fetch(url, {
                                        headers: { "Accept": "application/json" }
                                    }).then(r => r.json());

                                    if (result.used) {
                                        showSuccess();
                                        return;
                                    }

                                    if (!result.processing) {
                                        showError(result.message);
                                        return;
                                    }

                                    setTimeout(poll, 1000);
                                };

                                setTimeout(poll, 1000);
                            } catch (e) {
                                showError("티켓 확인 중 오류가 발생했습니다.");
                            }
                        }

                        start();
                    </script>
                </body>
                </html>
                """;
    }

    @GetMapping
    public ResponseEntity<List<TicketResponse>> mine(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(service.mine(current.id(jwt)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> one(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return ResponseEntity.ok(service.one(current.id(jwt), id));
    }

    @PostMapping("/reservations/{reservationId}")
    public ResponseEntity<TicketResponse> issue(@AuthenticationPrincipal Jwt jwt, @PathVariable Long reservationId) {
        return ResponseEntity.ok(service.issue(current.id(jwt), reservationId));
    }
}
