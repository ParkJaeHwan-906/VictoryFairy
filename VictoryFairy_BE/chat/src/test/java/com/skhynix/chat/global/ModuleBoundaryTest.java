package com.skhynix.chat.global;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 모듈 경계 — 앱 간 컴파일 의존 금지와 금지어 데이터 사본 금지(Gradle 이 테스트를 chat 모듈 디렉터리에서 실행한다). */
class ModuleBoundaryTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();

    @Test
    @DisplayName("[CHAT-GC-104] chat/build.gradle 에 project(':quiz') 의존이 없고 욕설 탐지는 project(':profanity') 로 받는다")
    void buildGradle_hasNoQuizDependency() throws IOException {
        String gradle = Files.readString(MODULE.resolve("build.gradle"));

        assertThat(gradle).doesNotContain("project(':quiz')").doesNotContain("project(':user')");
        assertThat(gradle).contains("implementation project(':profanity')");
    }

    @Test
    @DisplayName("[CHAT-GC-104] chat 메인 소스는 com.skhynix.quiz 패키지를 import 하지 않는다")
    void mainSources_doNotImportQuiz() throws IOException {
        try (Stream<Path> files = Files.walk(MODULE.resolve("src/main/java"))) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readString(p).contains("com.skhynix.quiz");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(offenders).isEmpty();
        }
    }

    @Test
    @DisplayName("[CHAT-GC-56] chat 모듈 안에 금지어 JSON 사본(banned_words 등)이 없다 — 데이터는 :profanity 한 곳에만 있다")
    void noProfanityDataCopyInChat() throws IOException {
        try (Stream<Path> files = Files.walk(MODULE.resolve("src/main"))) {
            List<String> jsons = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.equals("banned_words.json") || n.equals("exceptions.json")
                            || n.equals("normalization.json") || n.equals("whitespace_strict.json"))
                    .toList();
            assertThat(jsons).isEmpty();
        }
        assertThat(Files.exists(MODULE.getParent().resolve("profanity/src/main/resources/profanity/banned_words.json"))).isTrue();
    }

    @Test
    @DisplayName("[CHAT-GC-56] :profanity 모듈은 domain·web-support 를 참조하지 않는다(선행 리팩터 절의 의존 방향)")
    void profanityModule_doesNotDependOnDomainOrWebSupport() throws IOException {
        String gradle = Files.readString(MODULE.getParent().resolve("profanity/build.gradle"));

        assertThat(gradle).doesNotContain("project(':domain')").doesNotContain("project(':web-support')");
    }
}
