package com.skhynix.quiz.quiz.vote;

import java.util.List;
import java.util.Map;

/**
 * 퀴즈 보기별 투표 수 <b>적재</b> 포트. 읽는 쪽(분포 조회·SSE)은 이 인터페이스에 없다 — 후속 과제다.
 *
 * <p>호출부가 Redis 를 직접 알지 않게 하려고 둔 경계다. 구현은 저장소 장애를 <b>삼켜야</b> 한다 —
 * 집계는 부가 기능이고 제출·서빙이 본류라, 적재 실패가 호출자에게 전파돼 채점·포인트·응답을
 * 되돌리면 안 된다. 그래서 이 포트의 메서드는 성공 여부를 반환하지 않는다.
 *
 * <p>⚠ 여기서 말하는 <b>보기 번호는 {@code quiz_options.option} 값 그대로이며 0부터 시작한다</b>
 * (적재기가 후보 배열 인덱스를 그대로 넣고 정답도 {@code A→0} 으로 변환한다). 이름이 {@code optionNo}
 * 라 1부터라고 읽기 쉬운 자리이며, 1-based 로 넘기면 전 보기가 한 칸씩 어긋난다.
 */
public interface QuizVoteTally {

    /**
     * 한 표를 더한다. <b>제출 트랜잭션이 커밋된 뒤</b>에 부르는 것이 호출부의 계약이다 — 제출 경로는
     * 포인트 적립 때문에 계정 행 락을 쥐고 있어, 트랜잭션 안에서 부르면 락을 쥔 채 Redis 왕복을 한다.
     *
     * @param quizId   {@code quizzes.id}
     * @param optionNo 사용자가 고른 보기 번호(0-based)
     */
    void increment(long quizId, int optionNo);

    /**
     * 아직 표가 없는 보기도 0 으로 존재하게 만든다. <b>이미 값이 있는 필드는 건드리지 않는다</b> —
     * {@code /today} 는 같은 문제를 여러 사용자가 반복해서 받는 경로라, 덮어쓰면 나중 호출자가 이미
     * 쌓인 표를 0 으로 밀어 버린다.
     *
     * <p>초기화는 정확성이 아니라 <b>표현</b>을 위한 것이다 — 증가 연산은 필드가 없어도 0 에서
     * 시작하므로, 초기화를 건너뛰어도 최종 카운트는 같다. 달라지는 건 "아무도 안 고른 보기" 필드의
     * 유무뿐이다.
     *
     * @param optionNosByQuizId 문제 id → 그 문제의 보기 번호(0-based) 목록
     */
    void initialize(Map<Long, List<Integer>> optionNosByQuizId);
}
