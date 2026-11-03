package com.skhynix.domain.quizroom.repository;

import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizRoomRepository extends JpaRepository<QuizRoom, Long> {

    // 목록 변환이 author.nickname·profileImgUrl(LAZY)을 읽는다 — to-one 하나라 Pageable 과 안전하게 조합된다
    @EntityGraph(attributePaths = "author")
    Page<QuizRoom> findAllByOrderByIdDesc(Pageable pageable);

    @EntityGraph(attributePaths = "author")
    Optional<QuizRoom> findWithAuthorById(Long id);

    /**
     * 추천 변경 전용 — 방 행을 비관적 쓰기 락으로 잡아 같은 방의 {@code like_count} 갱신을 직렬화한다.
     * 조회 경로에 쓰지 말 것.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from QuizRoom r where r.id = :id")
    Optional<QuizRoom> findWithLockById(@Param("id") Long id);

    /**
     * 작성자를 통째로 다른 계정으로 넘긴다 — 호출자는 만료 데이터 정리 하나뿐이다.
     * 하나라도 남으면 NO ACTION FK 가 계정 삭제를 막는다.
     *
     * @return 이관된 방 수
     */
    @Modifying
    @Query("update QuizRoom r set r.author = :newOwner where r.author.id = :previousOwnerId")
    int reassignAuthor(@Param("previousOwnerId") Long previousOwnerId,
            @Param("newOwner") UserAccount newOwner);
}
