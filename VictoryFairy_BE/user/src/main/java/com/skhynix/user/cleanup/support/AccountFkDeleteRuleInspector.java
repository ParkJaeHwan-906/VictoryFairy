package com.skhynix.user.cleanup.support;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "계정이 지워질 때 행은 남고 소유자만 비워져야 하는" 테이블들의 계정 FK 가 실제로
 * {@code ON DELETE SET NULL} 인지 DB 에 직접 물어본다. 대상은 {@link #TARGETS} 하나의 목록이다 —
 * 종전에는 {@code quizzes_like} 하나였고 커뮤니티 반응 2테이블·신고 테이블이 합류했다.
 *
 * <p>이 검사가 막는 사고는 하나다: <b>FK 가 생각과 다른 DB 에서 스케줄러가 먼저 도는 것.</b> 그때 FK 가
 * CASCADE 면 계정을 지우는 순간 그 사람이 누른 추천·반응 행이 함께 사라지고, 문제별 추천 수와 게시글의
 * {@code like_count}(카운터 컬럼은 남는다)가 조용히 어긋난다 — 로그도 예외도 남지 않고 <b>되돌릴 수도
 * 없다.</b> NO ACTION 이면 반대로 계정 삭제 자체가 실패한다.
 *
 * <p>엔티티 매핑({@code @OnDelete})을 근거로 삼을 수 없는 이유: {@code ddl-auto=update} 는 <b>기존
 * FK 의 삭제 규칙을 바꾸지 않는다.</b> 즉 코드가 SET NULL 이라고 말해도 실제 DB 는 CASCADE 인
 * 상태가 정상적으로 존재하며, 그 어긋남을 볼 수 있는 곳은 {@code information_schema} 뿐이다.
 *
 * <p>회차마다(하루 1회) 조회한다. 기동 시 1회로 캐시하지 않는 이유는 마이그레이션이 <b>앱 재시작
 * 없이</b> 적용되기 때문이다 — 캐시하면 적용 후에도 다음 배포까지 정리가 멈춘 채로 있게 된다.
 *
 * <p>조회 자체가 실패하면 그 대상을 "아니오"로 답한다(fail-closed). 확인하지 못한 상태에서 지우는 것보다
 * 하루 미루는 편이 안전하고, 미뤄진 계정은 조건을 그대로 만족한 채 다음 회차에 다시 잡힌다.
 * ⚠ 그래서 <b>테이블이 없는 환경도 fail-closed 다</b>(FK 0개) — 커뮤니티 엔티티 없이 이 배치만 먼저
 * 배포하면 계정 삭제 단계가 통째로 멈춘다. 둘을 떼어 배포하지 말 것.
 */
@Component
@RequiredArgsConstructor
public class AccountFkDeleteRuleInspector {

    /**
     * 검사 대상 하나 — 테이블·계정 컬럼과, 어긋났을 때 사람이 적용할 1회성 DDL·이유(로그 문구용).
     */
    public record Target(String table, String column, String migration, String reason) {

        public String name() {
            return table + "." + column;
        }
    }

    public static final List<Target> TARGETS = List.of(
            new Target("quizzes_like", "user_account_id",
                    "infra/sql/migrate-quiz-like-account-set-null.sql",
                    "적용 전에 지우면 CASCADE 가 추천 행까지 지워 추천 수가 되돌릴 수 없이 줄어든다"),
            new Target("community_post_reactions", "user_account_id",
                    "infra/sql/migrate-community.sql",
                    "적용 전에 지우면 CASCADE 가 반응 행을 지우는데 like_count 카운터는 남아 "
                            + "카운터와 행 수가 되돌릴 수 없이 어긋난다"),
            new Target("community_comment_reactions", "user_account_id",
                    "infra/sql/migrate-community.sql",
                    "적용 전에 지우면 CASCADE 가 반응 행을 지우는데 like_count 카운터는 남아 "
                            + "카운터와 행 수가 되돌릴 수 없이 어긋난다"),
            new Target("community_reports", "reporter_account_id",
                    "infra/sql/migrate-community.sql",
                    "적용 전에 지우면 CASCADE 가 신고 행을 지워 블라인드의 근거가 사라진다"));

    private static final Logger log = LoggerFactory.getLogger(AccountFkDeleteRuleInspector.class);

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
              and rc.TABLE_NAME = ?
              and kcu.COLUMN_NAME = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 전부 통과했을 때만 {@code true}. 하나라도 어긋나면 어긋난 대상을 전부 확인한 뒤 {@code false} 다 —
     * 첫 실패에서 멈추지 않는 이유는 운영자가 한 회차 로그로 적용할 DDL 을 모두 알 수 있게 하기 위해서다.
     */
    public boolean isAllSetNull() {
        return findMisconfigured().isEmpty();
    }

    /**
     * @return 계정 FK 가 정확히 1개이고 그 삭제 규칙이 {@code SET NULL} 인 조건을 만족하지 못한 대상.
     *         조회에 실패한 대상도 포함된다(fail-closed). 비어 있으면 전부 정상
     */
    public List<Target> findMisconfigured() {
        List<Target> misconfigured = new ArrayList<>();
        for (Target target : TARGETS) {
            if (!isSetNull(target)) {
                misconfigured.add(target);
            }
        }
        return misconfigured;
    }

    private boolean isSetNull(Target target) {
        try {
            List<String> rules = jdbcTemplate.queryForList(DELETE_RULE_SQL, String.class,
                    target.table(), target.column());
            if (rules.size() != 1) {
                // 0건이면 FK 자체가 없는 환경(제약 없이 만들어진 테이블 또는 테이블 없음), 2건 이상이면 중복 FK 다.
                // 어느 쪽이든 "계정 삭제가 무엇을 하는지" 확신할 수 없으므로 진행하지 않는다.
                log.warn("{} 의 {} FK 가 {}개다 — 1개(SET NULL)여야 한다", target.table(), target.column(),
                        rules.size());
                return false;
            }
            return EXPECTED_RULE.equalsIgnoreCase(rules.get(0));
        } catch (RuntimeException e) {
            log.error("{} FK 삭제 규칙 조회 실패 — 확인되지 않았으므로 '아니오'로 간주한다", target.table(), e);
            return false;
        }
    }
}
