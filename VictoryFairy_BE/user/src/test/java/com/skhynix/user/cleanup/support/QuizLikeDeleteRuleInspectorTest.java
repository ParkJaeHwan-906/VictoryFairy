package com.skhynix.user.cleanup.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

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
 * {@link QuizLikeDeleteRuleInspector} 단위 테스트 — USER-EDC-49의 선행 검사(FK 삭제 규칙이 SET NULL이
 * 아니면 계정 삭제 단계를 멈춘다)가 실제로 information_schema 응답 형태별로 올바르게 갈리는지 검증한다.
 * 요구사항: {@code docs/requirements/user/expired-data-cleanup.md}.
 */
@ExtendWith(MockitoExtension.class)
class QuizLikeDeleteRuleInspectorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private QuizLikeDeleteRuleInspector inspector;

    @Test
    @DisplayName("[USER-EDC-49] FK 삭제 규칙이 정확히 1개이고 값이 SET NULL이면 true다(마이그레이션 적용 완료)")
    void isSetNull_exactlyOneSetNullRule_returnsTrue() {
        // given
        given(jdbcTemplate.queryForList(anyString(), eq(String.class))).willReturn(List.of("SET NULL"));

        // when / then
        assertThat(inspector.isSetNull()).isTrue();
    }

    @Test
    @DisplayName("MySQL이 대소문자를 다르게 돌려줘도(예: 소문자) SET NULL로 인식한다")
    void isSetNull_caseInsensitiveMatch_returnsTrue() {
        // given
        given(jdbcTemplate.queryForList(anyString(), eq(String.class))).willReturn(List.of("set null"));

        // when / then
        assertThat(inspector.isSetNull()).isTrue();
    }

    @Test
    @DisplayName("[USER-EDC-49] FK 삭제 규칙이 아직 CASCADE면(마이그레이션 미적용) false다"
            + " — 이 신호로 계정 삭제 단계가 통째로 멈춘다")
    void isSetNull_stillCascade_returnsFalse() {
        // given
        given(jdbcTemplate.queryForList(anyString(), eq(String.class))).willReturn(List.of("CASCADE"));

        // when / then
        assertThat(inspector.isSetNull()).isFalse();
    }

    @Test
    @DisplayName("FK 자체가 없는 환경(0건)이면 확신할 수 없으므로 false다(fail-closed)")
    void isSetNull_noConstraintFound_returnsFalse() {
        // given
        given(jdbcTemplate.queryForList(anyString(), eq(String.class))).willReturn(List.of());

        // when / then
        assertThat(inspector.isSetNull()).isFalse();
    }

    @Test
    @DisplayName("동일 컬럼에 FK가 2개 이상 잡히면(중복 제약) 확신할 수 없으므로 false다(fail-closed)")
    void isSetNull_multipleConstraintsFound_returnsFalse() {
        // given
        given(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .willReturn(List.of("SET NULL", "CASCADE"));

        // when / then
        assertThat(inspector.isSetNull()).isFalse();
    }

    @Test
    @DisplayName("조회 자체가 실패하면(DB 접근 오류 등) 확인되지 않았다고 보고 false다(fail-closed)")
    void isSetNull_queryFails_returnsFalse() {
        // given
        willThrow(new DataAccessResourceFailureException("connection lost"))
                .given(jdbcTemplate).queryForList(anyString(), eq(String.class));

        // when / then
        assertThat(inspector.isSetNull()).isFalse();
    }
}
