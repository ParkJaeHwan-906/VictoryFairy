package com.skhynix.domain.quiz.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * {@link QuizUserSubmitRepositoryCustom} 구현. 클래스명이 {@code <리포지토리 인터페이스>Impl} 이어야
 * Spring Data 가 프래그먼트로 엮는다 — 이름을 바꾸면 배선이 조용히 끊긴다.
 */
public class QuizUserSubmitRepositoryImpl implements QuizUserSubmitRepositoryCustom {

    // ⚠ ON DUPLICATE KEY UPDATE 로 자기 자신을 대입한다 — 아무 값도 바꾸지 않는 no-op 이다.
    //   ① 같은 사용자의 /today 두 요청이 동시에 같은 문제를 넣어도 uk_quiz_users_submit_account_quiz
    //      위반이 아예 발생하지 않아 "이미 출제됨"으로 흡수된다.
    //   ② 진 쪽이 created_at·game_id·inning 을 덮어쓰지 않는다 — 덮어쓰면 시한이 뒤로 밀려 연장
    //      수단이 되고, 이닝도 "처음 받은 이닝"이 아니라 "마지막으로 본 이닝"이 된다.
    //   ③ 예외를 잡아 삼키는 방식은 쓸 수 없다: 제약 위반은 트랜잭션을 rollback-only 로 표시해
    //      "삼키고 계속" 하면 커밋에서 UnexpectedRollbackException(500)이 된다(QuizLikeToggler 가 그
    //      함정을 트랜잭션 분리로 푼 선례). 반대로 이 구문은 중복 키 충돌만 흡수하고 FK·NOT NULL
    //      위반은 그대로 던진다 — "다른 무결성 오류까지 삼키지 않는다"가 계약이다.
    //   PK(id)를 대입 대상으로 삼지 않은 것은 AUTO_INCREMENT 컬럼을 건드리지 않기 위해서다.
    private static final String INSERT_PREFIX = "INSERT INTO quiz_users_submit "
            + "(user_account_id, quiz_id, game_id, submit_option_id, is_answer, inning, "
            + "created_at, updated_at) VALUES ";
    private static final String VALUES_TUPLE = "(?, ?, ?, NULL, 0, ?, ?, ?)";
    private static final String ON_DUPLICATE =
            " ON DUPLICATE KEY UPDATE user_account_id = user_account_id";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * <b>왕복 1회</b> — 튜플을 문제 수만큼 이어 붙인 단일 INSERT 다. {@code saveAll} 로는 안 된다:
     * PK 가 IDENTITY 라 Hibernate 가 JDBC 배치를 끄고 <b>행마다 왕복</b>하므로 상한(20건)이 그대로
     * 응답 시간에 실린다.
     *
     * <p>경기·이닝이 요청당 단일 값이라 튜플마다 같은 값을 반복 바인딩한다 — 값이 원시 타입이라
     * {@code null} 을 네이티브 쿼리에 바인딩할 때의 타입 추론 실패 문제가 없다.
     */
    @Override
    public int insertUnansweredRows(long userAccountId, Collection<Long> quizIds, long gameId,
            int inning, LocalDateTime servedAt) {
        if (quizIds.isEmpty()) {
            return 0; // 만들 것이 없으면 SQL 자체를 보내지 않는다
        }
        StringBuilder sql = new StringBuilder(INSERT_PREFIX);
        for (int i = 0; i < quizIds.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(VALUES_TUPLE);
        }
        Query query = entityManager.createNativeQuery(sql.append(ON_DUPLICATE).toString());
        int position = 1;
        for (Long quizId : quizIds) {
            query.setParameter(position++, userAccountId);
            query.setParameter(position++, quizId);
            query.setParameter(position++, gameId);
            query.setParameter(position++, inning);
            query.setParameter(position++, servedAt);
            query.setParameter(position++, servedAt);
        }
        return query.executeUpdate();
    }
}
