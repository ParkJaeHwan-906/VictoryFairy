package com.skhynix.domain.quiz.repository;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 파생 쿼리·JPQL 로 표현할 수 없는 {@code quiz_users_submit} 쓰기 — <b>domain 최초의 커스텀 프래그먼트</b>다.
 * 구현은 {@code QuizUserSubmitRepositoryImpl}(이름 규약: 리포지토리 인터페이스명 + {@code Impl}).
 */
public interface QuizUserSubmitRepositoryCustom {

    /**
     * 서빙한 문제들의 <b>미답 행</b>({@code submit_option_id IS NULL} · {@code is_answer = false})을
     * <b>한 문장으로</b> 만든다. 이미 행이 있는 문제는 아무것도 바꾸지 않는다.
     *
     * <p><b>{@code gameId}·{@code inning} 이 문제별 값이 아니라 요청당 단일 값인 것이 이 시그니처의
     * 핵심이다.</b> 이닝은 문제의 속성("이 문제가 몇 회짜리인가")이 아니라 요청자의 관전 시점("이
     * 사용자가 자기 팀 경기의 몇 회에 받았는가")이라, 한 요청으로 만들어지는 행은 전부 같은 값을
     * 갖는다. 둘 다 {@code null} 일 수 없다 — 경기·이닝을 특정하지 못한 요청은 애초에 여기까지 오지
     * 않고 세트 제공 자체가 거절된다.
     *
     * @param quizIds 행을 만들 문제들. 순서를 보존하는 컬렉션을 넘기면 INSERT 순서도 그대로다
     * @param gameId 요청자가 지목해 검증을 통과한 <b>기준 경기의 내부 PK</b>({@code naver_game_id}
     *     문자열이 아니다)
     * @param inning 그 경기의 {@code current_inning} — 회차 판정 키의 일부라 값이 있어야 한다
     * @param servedAt 행의 {@code created_at}/{@code updated_at} 에 넣을 시각 = 시한(+8분)의 기준점.
     *     ⚠ <b>호출부가 {@code LocalDateTime.now()}(JVM 기본 존)로 넘겨야 한다</b> — {@code @CreationTimestamp}
     *     가 쓰는 기준과 같아야 이 컬럼으로 하는 시한 계산이 기존 행과 뒤섞여도 일관된다. KST 고정 클록
     *     ({@code kstClock})은 파드의 JVM 기본 존 설정(k8s Deployment env 의 {@code TZ})과 별개로 코드에
     *     고정돼 있어, 그 둘이 어긋나면(설정 누락 등) 넘긴 시각이 {@code created_at} 과 어긋나
     *     <b>모든 행이 시한 오판</b>에 빠진다(QuizSubmitWindow javadoc 참고)
     * @return 실제로 만들어진 행 수(이미 있던 문제는 세지 않는다)
     */
    int insertUnansweredRows(long userAccountId, Collection<Long> quizIds, long gameId, int inning,
            LocalDateTime servedAt);
}
