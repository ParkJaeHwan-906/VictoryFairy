package com.skhynix.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// quiz 와 같은 이유로 좁게 스캔한다 — web-support·profanity 의 부품은 필요한 것만 @Import 로 끌어온다.
@SpringBootApplication(scanBasePackages = "com.skhynix.chat")
@EntityScan("com.skhynix")
@EnableJpaRepositories(basePackages = "com.skhynix")
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
