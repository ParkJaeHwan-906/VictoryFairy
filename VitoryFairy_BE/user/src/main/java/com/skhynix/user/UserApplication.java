package com.skhynix.user;

import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtVerificationConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// web-support로 옮겨간 두 빈/어드바이스는 컴포넌트 스캔(com.skhynix) 밖의 com.skhynix.websupport에
// 있어 자동 감지되지 않으므로 명시적으로 등록한다(이동 전 자동 스캔으로 얻던 것과 동일한 효과).
// JwtVerificationConfig: JwtTokenProvider 빈 · GlobalExceptionHandler: 예외 → ApiResponse @RestControllerAdvice
@Import({JwtVerificationConfig.class, GlobalExceptionHandler.class})
@SpringBootApplication(scanBasePackages = "com.skhynix")
@EntityScan("com.skhynix")
@EnableJpaRepositories(basePackages = "com.skhynix")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }

}
