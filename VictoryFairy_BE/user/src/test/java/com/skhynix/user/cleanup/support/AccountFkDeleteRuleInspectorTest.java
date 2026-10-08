package com.skhynix.user.cleanup.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.skhynix.user.cleanup.support.AccountFkDeleteRuleInspector.Target;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AccountFkDeleteRuleInspector} 단위 테스트 — USER-EDC-49·58의 선행 검사(대상 4개의 계정 FK 삭제 규칙이
 * SET NULL이 아니면 계정 삭제 단계를 멈춘다)가 information_schema 응답 형태별로 올바르게 갈리는지 검증한다.
 * 요구사항: {@code docs/requirements/user/expired-data-cleanup.md}.
 */
@ExtendWith(MockitoExtension.class)
class AccountFkDeleteRuleInspectorTest {

    private static final String QUIZ_LIKE = "quizzes_like";
    private static final String POST_REACTIONS = "community_post_reactions";
    private static final String COMMENT_REACTIONS = "community_comment_reactions";
    private static final String REPORTS = "community_reports";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AccountFkDeleteRuleInspector inspector;

    private void stubRule(String table, List<String> rules) {
        given(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(table), anyString()))
                .willReturn(rules);
    }

    private void stubAll(List<String> rules) {
        for (Target t : AccountFkDeleteRuleInspector.TARGETS) {
            stubRule(t.table(), rules);
        }
    }

    @Test
    @DisplayName("[USER-EDC-58] 검사 대상은 quizzes_like·커뮤니티 반응 2·신고 = 정확히 4개다")
    void targets_areTheFourTables() {
        assertThat(AccountFkDeleteRuleInspector.TARGETS).extracting(Target::table)
                .containsExactly(QUIZ_LIKE, POST_REACTIONS, COMMENT_REACTIONS, REPORTS);
        assertThat(AccountFkDeleteRuleInspector.TARGETS).extracting(Target::column)
                .containsExactly("user_account_id", "user_account_id", "user_account_id",
                        "reporter_account_id");
    }

    @Test
    @DisplayName("[USER-EDC-49, USER-EDC-58] 4개 전부 정확히 1개이고 SET NULL이면 isAllSetNull=true, 미설정 목록은 비어 있다")
    void allSetNull_returnsTrue_andNoMisconfigured() {
        stubAll(List.of("SET NULL"));

        assertThat(inspector.isAllSetNull()).isTrue();
        assertThat(inspector.findMisconfigured()).isEmpty();
    }

    @Test
    @DisplayName("MySQL이 대소문자를 다르게 돌려줘도(소문자) SET NULL로 인식한다")
    void caseInsensitiveMatch_returnsTrue() {
        stubAll(List.of("set null"));

        assertThat(inspector.isAllSetNull()).isTrue();
    }

    @Test
    @DisplayName("[USER-EDC-58] 4개 전부 아직 CASCADE면 isAllSetNull=false이고 4개 모두 목록에 담긴다")
    void allCascade_returnsFalse_andReportsEveryTarget() {
        stubAll(List.of("CASCADE"));

        assertThat(inspector.isAllSetNull()).isFalse();
        assertThat(inspector.findMisconfigured()).containsExactlyElementsOf(AccountFkDeleteRuleInspector.TARGETS);
    }

    @Test
    @DisplayName("[USER-EDC-58] 한 테이블(community_post_reactions)만 CASCADE면 그 테이블만 미설정으로 보고된다")
    void onlyOneTableCascade_reportsOnlyThatTable() {
        stubRule(QUIZ_LIKE, List.of("SET NULL"));
        stubRule(POST_REACTIONS, List.of("CASCADE"));
        stubRule(COMMENT_REACTIONS, List.of("SET NULL"));
        stubRule(REPORTS, List.of("SET NULL"));

        assertThat(inspector.isAllSetNull()).isFalse();
        assertThat(inspector.findMisconfigured()).extracting(Target::table).containsExactly(POST_REACTIONS);
    }

    @Test
    @DisplayName("[USER-EDC-58] 첫 실패에서 멈추지 않는다 — 두 테이블이 어긋나면 둘 다 보고된다(운영자가 한 회차 로그로 DDL 파악)")
    void twoTablesWrong_bothReported() {
        stubRule(QUIZ_LIKE, List.of("CASCADE"));
        stubRule(POST_REACTIONS, List.of("SET NULL"));
        stubRule(COMMENT_REACTIONS, List.of("SET NULL"));
        stubRule(REPORTS, List.of("NO ACTION"));

        assertThat(inspector.findMisconfigured()).extracting(Target::table)
                .containsExactly(QUIZ_LIKE, REPORTS);
    }

    @Test
    @DisplayName("FK 자체가 없는 환경(0건, 테이블 없음 포함)은 확신할 수 없으므로 미설정이다(fail-closed)")
    void noConstraintFound_isMisconfigured() {
        stubAll(List.of());

        assertThat(inspector.isAllSetNull()).isFalse();
        assertThat(inspector.findMisconfigured()).hasSize(4);
    }

    @Test
    @DisplayName("동일 컬럼에 FK가 2개 이상 잡히면(중복 제약) 미설정이다(fail-closed)")
    void multipleConstraintsFound_isMisconfigured() {
        stubRule(QUIZ_LIKE, List.of("SET NULL", "CASCADE"));
        stubRule(POST_REACTIONS, List.of("SET NULL"));
        stubRule(COMMENT_REACTIONS, List.of("SET NULL"));
        stubRule(REPORTS, List.of("SET NULL"));

        assertThat(inspector.findMisconfigured()).extracting(Target::table).containsExactly(QUIZ_LIKE);
    }

    @Test
    @DisplayName("[USER-EDC-58] 한 테이블의 조회만 실패해도 그 테이블만 미설정으로 간주하고 나머지 검사는 계속한다")
    void oneQueryFails_thatTableMisconfigured_othersStillChecked() {
        stubRule(QUIZ_LIKE, List.of("SET NULL"));
        willThrow(new DataAccessResourceFailureException("connection lost")).given(jdbcTemplate)
                .queryForList(anyString(), eq(String.class), eq(COMMENT_REACTIONS), anyString());
        stubRule(POST_REACTIONS, List.of("SET NULL"));
        stubRule(REPORTS, List.of("SET NULL"));

        assertThat(inspector.isAllSetNull()).isFalse();
        assertThat(inspector.findMisconfigured()).extracting(Target::table).containsExactly(COMMENT_REACTIONS);
    }

    @Test
    @DisplayName("조회 전체가 실패하면(DB 접근 오류) 4개 모두 미설정으로 간주한다(fail-closed)")
    void allQueriesFail_everyTargetMisconfigured() {
        willThrow(new DataAccessResourceFailureException("down")).given(jdbcTemplate)
                .queryForList(anyString(), eq(String.class), anyString(), anyString());

        assertThat(inspector.isAllSetNull()).isFalse();
        assertThat(inspector.findMisconfigured()).hasSize(4);
    }
}
