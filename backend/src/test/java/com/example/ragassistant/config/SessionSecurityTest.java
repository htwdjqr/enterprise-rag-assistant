package com.example.ragassistant.config;

import com.example.ragassistant.domain.CurrentUser;
import com.example.ragassistant.repository.UserRepository;
import com.example.ragassistant.web.SessionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实安全过滤器验证 Cookie 会话、会话固定防护和退出；不连接数据库或模型。 */
@WebMvcTest(SessionController.class)
@Import(SecurityConfig.class)
class SessionSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @MockitoBean UserRepository users;

    @BeforeEach void account() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(new CurrentUser(
                "alice-id", "tenant", "alice", passwords.encode("test-password"), true)));
    }

    @Test void loginPersistsIdentityAndLogoutInvalidatesSession() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        var session = new MockHttpSession();
        String previousId = session.getId();
        var response = mvc.perform(post("/api/login").session(session).with(csrf())
                .param("username", "alice").param("password", "test-password"))
                .andExpect(status().isNoContent()).andReturn();
        var authenticated = (MockHttpSession) response.getRequest().getSession(false);
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getId()).isNotEqualTo(previousId);
        mvc.perform(get("/api/me").session(authenticated))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"));
        mvc.perform(post("/api/logout").session(authenticated)).andExpect(status().isForbidden());
        mvc.perform(post("/api/logout").session(authenticated).with(csrf())).andExpect(status().isNoContent());
        assertThat(authenticated.isInvalid()).isTrue();
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test void loginRequiresCsrfAndRejectsWrongPasswordWhileBasicRemainsAvailable() throws Exception {
        mvc.perform(post("/api/login").param("username", "alice").param("password", "test-password"))
                .andExpect(status().isForbidden());
        var failed = mvc.perform(post("/api/login").with(csrf())
                .param("username", "alice").param("password", "wrong"))
                .andExpect(status().isUnauthorized()).andReturn();
        var session = (MockHttpSession) failed.getRequest().getSession(false);
        if (session != null) mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").with(httpBasic("alice", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"));
    }
}
