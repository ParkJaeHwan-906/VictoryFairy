package com.skhynix.user.community;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 카테고리 시드 SQL 과 그 등록 설정의 <b>정적 계약</b> - DB 없이 클래스패스에 실린 파일 텍스트로 확인한다
 * (user 의 {@code processResources} 가 {@code infra/sql/*.sql} 을 {@code sql/} 로 복사한다). 시드가 실제로 11행을
 * 만드는지(AC-CM-12-2, 13-1 의 실행 결과)는 MySQL 이 있어야 해서 여기서 검증하지 못한다.
 * 요구사항: {@code docs/requirements/user/community.md} USER-CM-10~13, 202, 203.
 */
class CommunitySeedAndConfigTest {

    private static String read(String classpathLocation) throws IOException {
        try (InputStream in = CommunitySeedAndConfigTest.class.getClassLoader()
                .getResourceAsStream(classpathLocation)) {
            assertThat(in).as(classpathLocation + " 이 클래스패스에 있어야 한다").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> codesIn(String sql) {
        List<String> codes = new ArrayList<>();
        Matcher m = Pattern.compile("'([A-Z]{2})'").matcher(sql);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    @Test
    @DisplayName("[AC-CM-12-1, AC-CM-203-1] 시드 파일과 1회성 DDL 파일이 존재한다")
    void seedAndMigrationFilesExist() throws IOException {
        assertThat(read("sql/community-categories-init.sql")).isNotBlank();
        assertThat(read("sql/migrate-community.sql")).isNotBlank();
    }

    @Test
    @DisplayName("[AC-CM-13-1, AC-CM-10-1] 구단 카테고리 10개는 teams.code로 team_id를 해석하며(id 하드코딩 없음) 코드 10개가 teams 시드의 코드와 모두 일치한다")
    void teamCategories_resolveTeamIdByCode_andCodesMatchTeamsSeed() throws IOException {
        String seed = read("sql/community-categories-init.sql");
        String teams = read("sql/teams-init.sql");

        List<String> seedCodes = codesIn(seed.substring(seed.indexOf("INSERT INTO"), seed.indexOf("-- Step 2")));

        assertThat(seed).contains("JOIN teams t ON t.code = seed.code");
        assertThat(seedCodes).hasSize(10).doesNotHaveDuplicates();
        assertThat(codesIn(teams)).containsAll(seedCodes);
    }

    @Test
    @DisplayName("[AC-CM-11-4, AC-CM-11-3] 구단 카테고리 name은 teams.name을 그대로 쓰고, 자유게시판은 team_id NULL + sort_order 11(구단 뒤)이다")
    void categoryNames_comeFromTeamsAndFreeBoardIsLast() throws IOException {
        String seed = read("sql/community-categories-init.sql");

        assertThat(seed).contains("SELECT t.name, t.id, seed.sort_order");
        assertThat(seed).contains("SELECT '자유게시판', NULL, 11");
    }

    @Test
    @DisplayName("[AC-CM-12-2] 시드는 재실행 안전하다 - 두 INSERT 모두 name 기준 NOT EXISTS anti-join이고 기존 행을 UPDATE/DELETE 하지 않는다")
    void seed_isIdempotentByName() throws IOException {
        String seed = read("sql/community-categories-init.sql");
        String executable = seed.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + b + "\n");

        assertThat(executable.split("INSERT INTO", -1)).hasSize(3);
        assertThat(executable.split("NOT EXISTS \\(SELECT 1 FROM community_categories c WHERE c\\.name", -1))
                .hasSize(3);
        assertThat(executable).doesNotContainIgnoringCase("UPDATE ").doesNotContainIgnoringCase("DELETE ")
                .doesNotContainIgnoringCase("TRUNCATE");
    }

    @Test
    @DisplayName("[AC-CM-12-1, AC-CM-13-1] dev·prod 프로파일 모두 data-locations에 카테고리 시드가 등록돼 있고 teams-init 뒤에 있다")
    void dataLocations_registerCategorySeedAfterTeams_inBothProfiles() throws IOException {
        for (String profile : List.of("application-dev.yaml", "application-prod.yaml")) {
            String yaml = read(profile);
            int teams = yaml.indexOf("classpath:sql/teams-init.sql");
            int category = yaml.indexOf("classpath:sql/community-categories-init.sql");

            assertThat(teams).as(profile + " teams-init").isGreaterThanOrEqualTo(0);
            assertThat(category).as(profile + " community-categories-init").isGreaterThan(teams);
            assertThat(yaml.split("classpath:sql/community-categories-init.sql", -1)).as(profile).hasSize(2);
        }
    }

    @Test
    @DisplayName("[AC-CM-202-1] 1회성 DDL은 UNIQUE 4건 이름과 반응 2·신고 1의 SET NULL 전환을 다룬다(적용 전 조회 포함)")
    void migrationScript_coversUniqueAndSetNull() throws IOException {
        String migration = read("sql/migrate-community.sql");

        assertThat(migration).contains("uk_community_categories_name",
                "uk_community_post_reactions_account_post",
                "uk_community_comment_reactions_account_comment",
                "uk_community_reports_reporter_target");
        assertThat(migration).contains("ON DELETE SET NULL");
        assertThat(migration).contains("information_schema");
    }
}
