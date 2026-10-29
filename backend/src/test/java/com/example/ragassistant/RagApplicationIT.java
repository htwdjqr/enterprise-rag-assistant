package com.example.ragassistant;

import com.example.ragassistant.domain.DocumentStatus;
import com.example.ragassistant.repository.DocumentRepository;
import com.example.ragassistant.repository.UserRepository;
import com.example.ragassistant.storage.LocalFileStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真 MySQL + 真 Qdrant + 真 Flyway + 真 HTTP 安全过滤器。
 * 模型使用三维确定性测试替身，因此验证工程链路与隔离，不衡量真实语义召回或回答质量。
 * 只有 mvn -Pintegration verify 才运行；容器在测试结束时由 Testcontainers 清理。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(RagApplicationIT.TestModels.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RagApplicationIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13")
            .withDatabaseName("rag_it").withUsername("rag_it").withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.20.0")
            .withExposedPorts(6333, 6334).waitingFor(Wait.forHttp("/readyz").forPort(6333));
    static final String PASSWORD = UUID.randomUUID().toString();
    static final Path FILES = Path.of("target", "it-files-" + UUID.randomUUID()).toAbsolutePath();

    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
        // 此测试只验证数据库与业务链路；关闭真实供应商的自动装配，防止调用外部收费服务。
        // HTTP 协议及实际 Spring AI 客户端由 CloudModelConfigurationTest 单独验证。
        r.add("spring.ai.model.chat", () -> "none");
        r.add("spring.ai.model.embedding", () -> "none");
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.ai.vectorstore.qdrant.host", QDRANT::getHost);
        r.add("spring.ai.vectorstore.qdrant.port", () -> QDRANT.getMappedPort(6334));
        r.add("spring.ai.vectorstore.qdrant.api-key", () -> "");
        r.add("app.bootstrap.enabled", () -> true);
        r.add("app.bootstrap.alice-password", () -> PASSWORD);
        r.add("app.bootstrap.bob-password", () -> PASSWORD);
        r.add("app.worker.poll-delay-ms", () -> 100);
        r.add("app.rag.storage-root", FILES::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DocumentRepository docs;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired LocalFileStorage storage;
    @Autowired VectorStore vectors;

    @AfterAll static void stopPollingBeforeContainers(@Autowired ThreadPoolTaskScheduler scheduler) {
        // 先停止轮询，容器停止后不再查询数据库；应用上下文仍交给 Spring 的测试生命周期关闭。
        scheduler.shutdown();
    }

    @Test void authenticatedUploadPermissionQuestionRevokeAndPhysicalDelete() throws Exception {
        mvc.perform(get("/api/knowledge-bases")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/knowledge-bases").with(httpBasic("alice", PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"blocked\"}"))
                .andExpect(status().isForbidden()); // 没有 CSRF token 的写请求应失败。

        String kb = createKb();
        String documentId = upload(kb, "# 差旅制度\n住宿上限为每人每天 500 元。");
        waitFor(documentId, DocumentStatus.READY);

        ask(kb, "bob").andExpect(status().isNotFound());
        mvc.perform(get("/api/knowledge-bases/" + kb + "/members").with(httpBasic("bob", PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + documentId + "/file").with(httpBasic("bob", PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/knowledge-bases/" + kb + "/members").with(httpBasic("alice", PASSWORD)).with(realCsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/knowledge-bases/" + kb + "/members").with(httpBasic("alice", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].username").value("bob"))
                .andExpect(jsonPath("$[1].role").value("READER"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
        mvc.perform(get("/api/knowledge-bases/" + kb + "/members").with(httpBasic("bob", PASSWORD)))
                .andExpect(status().isForbidden());
        ask(kb, "bob").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.sources[0].documentId").value(documentId))
                .andExpect(jsonPath("$.answer").value("住宿上限为每人每天 500 元。[1]"));
        mvc.perform(delete("/api/documents/" + documentId).with(httpBasic("bob", PASSWORD)).with(realCsrf()))
                .andExpect(status().isForbidden());

        // 同样的租户、不同知识库也必须隔离，即使向量非常相近。
        String emptyKb = createKb();
        ask(emptyKb, "alice").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_EVIDENCE"));
        String otherDocument = upload(emptyKb, "另一知识库的住宿资料，不能混入第一个知识库。");
        waitFor(otherDocument, DocumentStatus.READY);
        ask(kb, "alice").andExpect(status().isOk()).andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].documentId").value(documentId));
        users.insert("another-tenant", "outsider", passwords.encode(PASSWORD));
        ask(kb, "outsider").andExpect(status().isNotFound());
        mvc.perform(get("/api/knowledge-bases/" + kb + "/members").with(httpBasic("outsider", PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/knowledge-bases/" + kb + "/members").with(httpBasic("alice", PASSWORD)).with(realCsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"outsider\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/knowledge-bases/" + kb + "/members/bob").with(httpBasic("alice", PASSWORD)).with(realCsrf()))
                .andExpect(status().isNoContent());
        ask(kb, "bob").andExpect(status().isNotFound());

        var stored = docs.find(documentId).orElseThrow();
        mvc.perform(get("/api/knowledge-bases/" + kb + "/members").with(httpBasic("alice", PASSWORD)))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/documents/" + documentId).with(httpBasic("alice", PASSWORD)).with(realCsrf()))
                .andExpect(status().isAccepted());
        waitFor(documentId, DocumentStatus.DELETED);
        assertThat(storage.resolve(stored.storageKey())).doesNotExist();
        var filter = new FilterExpressionBuilder().eq("document_id", documentId).build();
        assertThat(vectors.similaritySearch(SearchRequest.builder().query("住宿").filterExpression(filter).build())).isEmpty();
        ask(kb, "alice").andExpect(jsonPath("$.status").value("NO_EVIDENCE"));
    }

    @Test void failedParsingCanBeRetriedAndCannotBeRetriedWhileReady() throws Exception {
        String kb = createKb();
        String id = upload(kb, " \n \t ");
        waitFor(id, DocumentStatus.FAILED);
        var doc = docs.find(id).orElseThrow();
        // 测试模拟管理员修复故障文件；实际用户应删除损坏文档后重新上传。
        Files.writeString(storage.resolve(doc.storageKey()), "住宿上限 500 元。");
        mvc.perform(post("/api/documents/" + id + "/retry").with(httpBasic("alice", PASSWORD)).with(realCsrf()))
                .andExpect(status().isAccepted());
        waitFor(id, DocumentStatus.READY);
        assertThat(docs.find(id).orElseThrow().attempts()).isEqualTo(2);
        mvc.perform(post("/api/documents/" + id + "/retry").with(httpBasic("alice", PASSWORD)).with(realCsrf()))
                .andExpect(status().isConflict());
    }

    @Test void csrfEndpointSupportsRealCookieAndHeaderRoundTrip() throws Exception {
        var tokenResponse = mvc.perform(get("/api/csrf").with(httpBasic("alice", PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode token = json.readTree(tokenResponse.getContentAsString());
        mvc.perform(post("/api/knowledge-bases").with(httpBasic("alice", PASSWORD))
                .cookie(tokenResponse.getCookies()).header(token.get("headerName").asText(), token.get("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CSRF 验证\"}"))
                .andExpect(status().isCreated());
    }

    @Test @Transactional void finishingIndexCannotOverwriteConcurrentDeleteAndRecoveryPreservesIntent() throws Exception {
        String kb = createKb();
        String id = UUID.randomUUID().toString();
        // 事务内的数据不会被后台线程看见，可确定性模拟“索引完成与删除请求”的竞争。
        docs.insert(id, kb, "demo-company", "race.md", id + ".md", 1, "test-hash");
        assertThat(docs.claim(id, DocumentStatus.PENDING, DocumentStatus.INDEXING)).isTrue();
        docs.requestDelete(id);
        docs.ready(id, 5);
        assertThat(docs.find(id).orElseThrow().status()).isEqualTo(DocumentStatus.DELETE_PENDING);
        docs.claim(id, DocumentStatus.DELETE_PENDING, DocumentStatus.DELETING);
        docs.recoverInterruptedTasks();
        assertThat(docs.find(id).orElseThrow().status()).isEqualTo(DocumentStatus.DELETE_PENDING);

        String interrupted = UUID.randomUUID().toString();
        docs.insert(interrupted, kb, "demo-company", "recover.md", interrupted + ".md", 1, "test-hash");
        docs.claim(interrupted, DocumentStatus.PENDING, DocumentStatus.INDEXING);
        docs.recoverInterruptedTasks();
        assertThat(docs.find(interrupted).orElseThrow().status()).isEqualTo(DocumentStatus.PENDING);
    }

    private RequestPostProcessor realCsrf() throws Exception {
        // 不使用 csrf() 测试捷径：它会替换安全过滤器的 token repository，无法验证真实 Cookie 行为。
        var response = mvc.perform(get("/api/csrf").with(httpBasic("alice", PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode token = json.readTree(response.getContentAsString());
        assertThat(response.getCookies()).isNotEmpty();
        return request -> {
            request.setCookies(response.getCookies());
            request.addHeader(token.get("headerName").asText(), token.get("token").asText());
            return request;
        };
    }

    private String createKb() throws Exception {
        var response = mvc.perform(post("/api/knowledge-bases").with(httpBasic("alice", PASSWORD)).with(realCsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"测试知识库\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asText();
    }

    private String upload(String kb, String text) throws Exception {
        var response = mvc.perform(multipart("/api/knowledge-bases/" + kb + "/documents")
                .file(new MockMultipartFile("file", "policy.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8)))
                .with(httpBasic("alice", PASSWORD)).with(realCsrf()))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asText();
    }

    private ResultActions ask(String kb, String username) throws Exception {
        return mvc.perform(post("/api/knowledge-bases/" + kb + "/questions")
                .with(httpBasic(username, PASSWORD)).with(realCsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"住宿上限是多少？\"}"));
    }

    private void waitFor(String id, DocumentStatus status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(docs.find(id).orElseThrow().status()).isEqualTo(status));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestModels {
        @Bean @Primary EmbeddingModel testEmbeddingModel() {
            return new EmbeddingModel() {
                @Override public EmbeddingResponse call(EmbeddingRequest request) {
                    return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size())
                            .mapToObj(i -> new Embedding(new float[]{1, 0, 0}, i)).toList());
                }
                @Override public float[] embed(Document document) { return new float[]{1, 0, 0}; }
                @Override public int dimensions() { return 3; }
            };
        }
        @Bean @Primary ChatModel testChatModel() {
            return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("住宿上限为每人每天 500 元。[1]"))));
        }
    }
}

