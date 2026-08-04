package com.skhynix.domain.chat.repository;

import com.skhynix.domain.chat.entity.Chatroom;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatroomRepository extends JpaRepository<Chatroom, Long> {

    List<Chatroom> findAllByDeletedAtIsNull();

    // 삭제됐거나 없는 방은 빈 결과 — 조회·구독·전송·히스토리 경로에서 404 판정 기준
    Optional<Chatroom> findByUidAndDeletedAtIsNull(String uid);
}
