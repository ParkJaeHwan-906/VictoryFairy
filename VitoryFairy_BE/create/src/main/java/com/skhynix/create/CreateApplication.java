package com.skhynix.create;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.skhynix")
@EntityScan("com.skhynix")
@EnableJpaRepositories(basePackages = "com.skhynix")
public class CreateApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreateApplication.class, args);
    }

}
