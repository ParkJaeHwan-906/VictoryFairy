package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.QuizType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizTypeRepository extends JpaRepository<QuizType, Long> {

    /**
     * 유형명으로 코드 행을 찾는다. 시드/출제 경로의 lookup-or-create 용
     * ({@code GameStatusRepository.findByName}·{@code PositionRepository.findByName}과 같은 계열).
     *
     * <p>{@code quiz_type.name}에 UNIQUE 가 없어 같은 이름이 두 행이면 예외가 난다 — 그 상황은
     * 스키마가 아니라 쓰기 경로가 막는다.
     */
    Optional<QuizType> findByName(String name);
}
