package smartticketing.auth;

import smartticketing.entity.enums.SocialProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2SuccessHandler
        extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthService authService;
    private final String frontend;

    public OAuth2SuccessHandler(
            AuthService authService,
            @Value("${app.frontend-url:http://localhost:5173}")
            String frontend
    ) {
        this.authService = authService;
        this.frontend = frontend;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {

        OAuth2AuthenticationToken oauth =
                (OAuth2AuthenticationToken) authentication;

        SocialProvider provider =
                SocialProvider.valueOf(
                        oauth.getAuthorizedClientRegistrationId()
                                .toUpperCase()
                );

        HttpSession session =
                request.getSession(true);

        Object linkUserId =
                session.getAttribute(
                        CustomOAuth2UserService.LINK_USER_ID
                );

        if (linkUserId != null) {

            session.removeAttribute(
                    CustomOAuth2UserService.LINK_USER_ID
            );

            String providerUserId =
                    String.valueOf(
                            oauth.getPrincipal()
                                    .getAttributes()
                                    .get("providerUserId")
                    );

            Object emailObject =
                    oauth.getPrincipal()
                            .getAttributes()
                            .get("email");

            String email =
                    emailObject == null
                            ? null
                            : String.valueOf(emailObject);

            authService.linkSocialAccount(
                    Long.valueOf(
                            String.valueOf(linkUserId)
                    ),
                    provider,
                    providerUserId,
                    email
            );

            getRedirectStrategy().sendRedirect(
                    request,
                    response,
                    frontend + "/#/profile"
            );

            return;
        }

        String providerUserId = String.valueOf(oauth.getPrincipal().getAttributes().get("providerUserId"));
        try {
            var token = authService.socialLogin(provider, providerUserId);
            if (token != null) {
                session.invalidate();
                getRedirectStrategy().sendRedirect(request, response,
                        frontend + "/#/oauth2/callback?token=" + token.accessToken());
                return;
            }
        } catch (IllegalArgumentException error) {
            session.invalidate();
            getRedirectStrategy().sendRedirect(request, response,
                    frontend + "/#/oauth2/callback?error=" + java.net.URLEncoder.encode(error.getMessage(), java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        getRedirectStrategy().sendRedirect(
                request,
                response,
                frontend
                        + "/#/signup/social?social="
                        + provider.name()
        );
    }
}
