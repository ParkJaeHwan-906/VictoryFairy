package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.QuizOption;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizOptionRepository extends JpaRepository<QuizOption, Long> {

    /**
     * 한 문제의 보기를 화면 표기 순서로 가져온다. 정렬 축이 PK 나 생성 순서가 아니라
     * {@code option}(보기 번호)인 것이 핵심이다 — 보기를 다시 만들어도 표기 순서가 흔들리지 않는다.
     *
     * <p>연관 경로를 {@code Quiz_Id}로 끊어 {@code quiz.id}(FK 값)로만 해석되게 했다
     * ({@code PlayerRepository.findAllByTeam_Id...}와 같은 이유).
     *
     * <p><b>{@code (quiz_id, option)} 복합 인덱스를 일부러 두지 않았다.</b> InnoDB 가 FK 에 자동으로 만드는
     * {@code quiz_id} 단일 인덱스로 레인지 스캔한 뒤 {@code Using filesort} 가 붙지만, 정렬 대상이 한 문제의
     * 보기 2~5행뿐이라 없애서 얻을 게 없다(실측: 보기 4행 기준 {@code ref} + filesort). 실행계획에
     * filesort 가 보인다고 인덱스를 더하지 말 것 — 다만 {@code (quiz_id, option)} UNIQUE 를 걸기로
     * 결정하면 그 제약이 같은 인덱스를 만들어 filesort 도 자연히 사라진다.
     *
     * <p>⚠ <b>목록 화면을 만들 때 이 메서드를 문제마다 부르면 그대로 N+1 이다.</b> {@code Quiz} 에는
     * {@code @OneToMany options} 가 없어서 {@code @EntityGraph} 로는 못 막는다 — 문제 N건의 보기가
     * 필요하면 {@code quiz_id in (...)} 한 방으로 받아 메모리에서 묶는 2쿼리 방식으로 갈 것.
     * 반대로 {@code @OneToMany} 를 새로 달아 fetch join + {@code Pageable} 을 같이 쓰면 Hibernate 가
     * 전체를 메모리로 올려 페이징한다({@code HHH90003004}) — 컨테이너가 {@code mem_limit} 로 묶여 있어
     * OOM-kill 로 이어진다. 이 조합은 금지.
     */
    List<QuizOption> findAllByQuiz_IdOrderByOptionAsc(Long quizId);
}
