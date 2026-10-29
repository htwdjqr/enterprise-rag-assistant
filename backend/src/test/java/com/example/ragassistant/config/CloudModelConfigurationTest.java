package com.example.ragassistant.config;

import com.example.ragassistant.rag.AnswerGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiChatAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiImageAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.zhipuai.ZhiPuAiEmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 加载工程真正的 application.yml 和两个供应商的自动配置，用本地 HTTP 替身检查协议。
 * 这里不 mock EmbeddingModel / ChatModel：会执行真实 Spring AI 客户端的序列化、鉴权和响应解析，
 * 但不向智普或 DeepSeek 发出网络请求，也不需要真实密钥或产生 API 费用。
 */
class CloudModelConfigurationTest {
    private final RestClient.Builder http = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class,
                    WebClientAutoConfiguration.class, SpringAiRetryAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class, ZhiPuAiEmbeddingAutoConfiguration.class,
                    ZhiPuAiChatAutoConfiguration.class, ZhiPuAiImageAutoConfiguration.class,
                    DeepSeekChatAutoConfiguration.class))
            .withBean(RestClient.Builder.class, () -> http)
            .withPropertyValues("ZHIPU_API_KEY=test-zhipu-only", "DEEPSEEK_API_KEY=test-deepseek-only");

    @Test void routesEmbeddingAndChatToSeparateProvidersWithTheirOwnCredentials() throws Exception {
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-zhipu-only"))
                .andExpect(jsonPath("$.model").value("embedding-3"))
                .andExpect(jsonPath("$.dimensions").value(2048))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andExpect(jsonPath("$.input[0]").value("差旅制度"))
                .andRespond(withSuccess(embeddingResponse(2, 2048), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-deepseek-only"))
                .andExpect(jsonPath("$.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.stream").value(false))
                .andRespond(withSuccess("""
                        {"id":"test-completion","object":"chat.completion","created":1,"model":"deepseek-flash",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"住宿上限 500 元。[1]"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}
                        """, MediaType.APPLICATION_JSON));

        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EmbeddingModel.class).hasSingleBean(ChatModel.class)
                    .doesNotHaveBean(ImageModel.class);
            assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(ZhiPuAiEmbeddingModel.class);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(DeepSeekChatModel.class);
            var vectors = context.getBean(EmbeddingModel.class).embed(List.of("差旅制度", "请假制度"));
            assertThat(vectors).hasSize(2).allSatisfy(v -> assertThat(v).hasSize(2048));
            String answer = new AnswerGenerator(context.getBean(ChatModel.class))
                    .answer("住宿上限是多少？", "[1] 住宿上限 500 元。");
            assertThat(answer).isEqualTo("住宿上限 500 元。[1]");
            server.verify();
        });
    }

    @Test void embedsOnlyTextAndSupportsConfiguredDimensions() throws Exception {
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/embeddings"))
                .andExpect(jsonPath("$.dimensions").value(1024))
                // Spring AI 的 Document 格式化器可能保留前置换行；关键是原文存在且没有元数据。
                .andExpect(jsonPath("$.input[0]").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("只嵌入这段文字"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-embed-this-id")))))
                .andRespond(withSuccess(embeddingResponse(1, 1024), MediaType.APPLICATION_JSON));
        runner.withPropertyValues("ZHIPU_EMBEDDING_DIMENSIONS=1024").run(context -> {
            assertThat(context).hasNotFailed();
            var vector = context.getBean(EmbeddingModel.class)
                    .embed(new Document("只嵌入这段文字", Map.of("tenant_id", "do-not-embed-this-id")));
            assertThat(vector).hasSize(1024);
            server.verify();
        });
    }

    @Test void unauthorizedEmbeddingResponseFailsInsteadOfReturningEmptyVectors() {
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/embeddings"))
                .andRespond(withUnauthorizedRequest());
        runner.run(context -> {
            assertThatThrownBy(() -> context.getBean(EmbeddingModel.class).embed("测试文本"))
                    .isInstanceOf(RuntimeException.class);
            server.verify();
        });
    }

    @Test void missingProviderKeyFailsAtStartup() {
        runner.withPropertyValues("ZHIPU_API_KEY=").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("DEEPSEEK_API_KEY=").run(context -> assertThat(context).hasFailed());
    }

    private String embeddingResponse(int count, int dimensions) throws Exception {
        var data = IntStream.range(0, count).mapToObj(i -> Map.of("object", "embedding", "index", i,
                "embedding", Collections.nCopies(dimensions, 0.01))).toList();
        return new ObjectMapper().writeValueAsString(Map.of("model", "embedding-3", "object", "list", "data", data,
                "usage", Map.of("prompt_tokens", 10, "total_tokens", 10)));
    }
}
