package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.QuizType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizTypeRepository extends JpaRepository<QuizType, Long> {

    /**
     * 유형명으로 코드 행을 찾는다. 시드/출제 경로의 lookup-or-create 용
     * ({@code GameStatusRepository.findByName}·{@code PositionRepository.findByName}과 같은 계열).
     *
     * <p>{@code Optional} 반환을 <b>스키마가 뒷받침한다</b> — {@code quiz_type.name} 에
     * {@code uk_quiz_type_name} UNIQUE 가 걸려 있어 같은 이름이 두 행이 되는 상태 자체가 만들어지지
     * 않는다({@code positions.name}·{@code stadiums.name} 은 아직 그 제약이 없어 다르다).
     */
    Optional<QuizType> findByName(String name);
}
