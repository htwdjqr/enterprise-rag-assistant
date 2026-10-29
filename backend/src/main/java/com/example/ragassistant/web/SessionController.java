package com.example.ragassistant.web;

import com.example.ragassistant.domain.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API 客户端先取 CSRF token，并在后续写请求里附带对应 Cookie 和请求头。 */
@RestController
public class SessionController {
    @GetMapping("/api/me")
    public ApiModels.Me me(@AuthenticationPrincipal CurrentUser user) {
        return new ApiModels.Me(user.id(), user.username(), user.tenantId());
    }

    @GetMapping("/api/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }
}
