package com.skhynix.domain.quiz.repository;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 파생 쿼리·JPQL 로 표현할 수 없는 {@code quiz_users_submit} 쓰기 — <b>domain 최초의 커스텀 프래그먼트</b>다.
 * 구현은 {@code QuizUserSubmitRepositoryImpl}(이름 규약: 리포지토리 인터페이스명 + {@code Impl}).
 */
public interface QuizUserSubmitRepositoryCustom {

    /**
     * 서빙한 문제들의 <b>미답 행</b>({@code submit_option_id IS NULL} · {@code is_answer = false})을
     * <b>한 문장으로</b> 만든다. 이미 행이 있는 문제는 아무것도 바꾸지 않는다.
     *
     * @param inningByQuizId 만들 행의 {@code 문제 → 이닝}. 이닝은 <b>서빙 시점 스냅샷</b>이고 미상이면
     *     {@code null} 이다(이닝을 못 구했다고 행을 건너뛰면 그 문제는 제출이 403 이 된다). 순서를 보존하는
     *     맵을 넘기면 INSERT 순서도 그대로다
     * @param servedAt 행의 {@code created_at}/{@code updated_at} 에 넣을 시각 = 시한(+8분)의 기준점.
     *     ⚠ <b>호출부가 {@code LocalDateTime.now()}(JVM 기본 존)로 넘겨야 한다</b> — {@code @CreationTimestamp}
     *     가 쓰는 기준과 같아야 이 컬럼으로 하는 시한 계산이 기존 행과 뒤섞여도 일관된다. KST 고정 클록
     *     ({@code kstClock})은 파드의 JVM 기본 존 설정(k8s Deployment env 의 {@code TZ})과 별개로 코드에
     *     고정돼 있어, 그 둘이 어긋나면(설정 누락 등) 넘긴 시각이 {@code created_at} 과 어긋나
     *     <b>모든 행이 시한 오판</b>에 빠진다(QuizSubmitWindow javadoc 참고)
     * @return 실제로 만들어진 행 수(이미 있던 문제는 세지 않는다)
     */
    int insertUnansweredRows(long userAccountId, Map<Long, Integer> inningByQuizId,
            LocalDateTime servedAt);
}
