package com.skhynix.user.cleanup.support;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code quizzes_like} 의 계정 FK 가 이미 {@code ON DELETE SET NULL} 인지 DB 에 직접 물어본다.
 *
 * <p>이 검사가 막는 사고는 하나다: <b>마이그레이션 적용 전에 스케줄러가 먼저 도는 것.</b> 그때 FK 는
 * 아직 CASCADE 라 계정을 지우는 순간 그 사람이 누른 추천 행이 함께 사라지고, 문제별 추천 수가
 * 조용히 줄어든다 — 로그도 예외도 남지 않고 <b>되돌릴 수도 없다.</b>
 *
 * <p>엔티티 매핑({@code @OnDelete})을 근거로 삼을 수 없는 이유: {@code ddl-auto=update} 는 <b>기존
 * FK 의 삭제 규칙을 바꾸지 않는다.</b> 즉 코드가 SET NULL 이라고 말해도 실제 DB 는 CASCADE 인
 * 상태가 정상적으로 존재하며, 그 어긋남을 볼 수 있는 곳은 {@code information_schema} 뿐이다.
 *
 * <p>회차마다(하루 1회) 조회한다. 기동 시 1회로 캐시하지 않는 이유는 마이그레이션이 <b>앱 재시작
 * 없이</b> 적용되기 때문이다 — 캐시하면 적용 후에도 다음 배포까지 정리가 멈춘 채로 있게 된다.
 *
 * <p>조회 자체가 실패하면 "아니오"로 답한다(fail-closed). 확인하지 못한 상태에서 지우는 것보다
 * 하루 미루는 편이 안전하고, 미뤄진 계정은 조건을 그대로 만족한 채 다음 회차에 다시 잡힌다.
 */
@Component
@RequiredArgsConstructor
public class QuizLikeDeleteRuleInspector {

    private static final Logger log = LoggerFactory.getLogger(QuizLikeDeleteRuleInspector.class);

    private static final String EXPECTED_RULE = "SET NULL";

    // FK 제약 이름으로 찾지 않는다 — 테이블을 Hibernate 가 만든 환경은 이름이 자동 생성값(FK...)이라
    // 환경마다 다르다. 컬럼으로 찾으면 이름과 무관하게 같은 답이 나온다.
    private static final String DELETE_RULE_SQL = """
            select rc.DELETE_RULE
            from information_schema.REFERENTIAL_CONSTRAINTS rc
            join information_schema.KEY_COLUMN_USAGE kcu
              on kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
             and kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
            where rc.CONSTRAINT_SCHEMA = database()
              and rc.TABLE_NAME = 'quizzes_like'
              and kcu.COLUMN_NAME = 'user_account_id'
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @return 계정 FK 가 정확히 1개이고 그 삭제 규칙이 {@code SET NULL} 일 때만 {@code true}
     */
    public boolean isSetNull() {
        try {
            List<String> rules = jdbcTemplate.queryForList(DELETE_RULE_SQL, String.class);
            if (rules.size() != 1) {
                // 0건이면 FK 자체가 없는 환경(제약 없이 만들어진 테이블), 2건 이상이면 중복 FK 다.
                // 어느 쪽이든 "계정 삭제가 무엇을 하는지" 확신할 수 없으므로 진행하지 않는다.
                log.warn("quizzes_like 의 user_account_id FK 가 {}개다 — 1개(SET NULL)여야 한다", rules.size());
                return false;
            }
            return EXPECTED_RULE.equalsIgnoreCase(rules.get(0));
        } catch (RuntimeException e) {
            log.error("quizzes_like FK 삭제 규칙 조회 실패 — 확인되지 않았으므로 '아니오'로 간주한다", e);
            return false;
        }
    }
}
