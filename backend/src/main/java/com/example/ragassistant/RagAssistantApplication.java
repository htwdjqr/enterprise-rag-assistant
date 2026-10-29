package com.example.ragassistant;

import com.example.ragassistant.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 应用入口：开启持久化文档任务的定时消费。 */
@SpringBootApplication
// 让 Spring 注册并调用 @Scheduled 方法；DocumentWorker 的轮询由此启用，不靠 Controller 触发。
@EnableScheduling
// 将 app.rag 配置绑定成 RagProperties Bean，供提取、分块和向量写入组件共用。
@EnableConfigurationProperties(RagProperties.class)
public class RagAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagAssistantApplication.class, args);
    }
}
