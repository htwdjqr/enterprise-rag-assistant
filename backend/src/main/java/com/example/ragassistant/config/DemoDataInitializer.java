package com.example.ragassistant.config;

import com.example.ragassistant.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 仅在显式开启时创建演示账号；密码来自环境变量，不在代码中预设通用密码。 */
@Component
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String alicePassword;
    private final String bobPassword;

    public DemoDataInitializer(UserRepository users, PasswordEncoder encoder,
            @Value("${app.bootstrap.alice-password}") String alicePassword,
            @Value("${app.bootstrap.bob-password}") String bobPassword) {
        this.users = users;
        this.encoder = encoder;
        this.alicePassword = alicePassword;
        this.bobPassword = bobPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createIfMissing("alice", alicePassword);
        createIfMissing("bob", bobPassword);
    }

    private void createIfMissing(String username, String password) {
        if (users.findByUsername(username).isPresent()) return;
        if (password.length() < 12 || password.startsWith("<")) {
            throw new IllegalStateException("初始化账号的密码必须配置为至少 12 位的非占位符值");
        }
        users.insert("demo-company", username, encoder.encode(password));
    }
}
