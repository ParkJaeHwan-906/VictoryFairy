package com.skhynix.domain.chat.repository;

import com.skhynix.domain.chat.entity.Chatroom;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatroomRepository extends JpaRepository<Chatroom, Long> {

    List<Chatroom> findAllByDeletedAtIsNull();

    // 채팅방은 구단 단위 폐쇄 공간 — 목록 조회는 요청자의 응원 구단 방만 본다
    List<Chatroom> findAllByTeam_IdAndDeletedAtIsNull(Long teamId);

    // 삭제됐거나 없는 방은 빈 결과 — 조회·구독·전송·히스토리 경로에서 404 판정 기준
    Optional<Chatroom> findByUidAndDeletedAtIsNull(String uid);
}
