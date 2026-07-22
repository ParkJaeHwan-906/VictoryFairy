package com.skhynix.domain.chat.repository;

import com.skhynix.domain.chat.entity.Chatroom;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatroomRepository extends JpaRepository<Chatroom, Long> {

    /**
     * 소프트 삭제되지 않은 방만 조회한다(목록 노출용). {@code deletedAt}이 채워진 방은 제외된다.
     */
    List<Chatroom> findAllByDeletedAtIsNull();

    /**
     * 외부 식별자 {@code uid}로 소프트 삭제되지 않은 방을 조회한다. 삭제됐거나 없는 방은 빈 결과가 되어
     * 조회·구독·전송·히스토리 경로에서 404로 취급된다.
     */
    Optional<Chatroom> findByUidAndDeletedAtIsNull(String uid);
}
