package com.skhynix.domain.quizroom.repository;

import com.skhynix.domain.quizroom.entity.QuizRoomWeeklyBest;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRoomWeeklyBestRepository extends JpaRepository<QuizRoomWeeklyBest, Long> {

    // 인기 목록 변환이 room.author.nickname·profileImgUrl(LAZY)을 읽는다 — 최대 5행이라 to-one 그래프로 한 번에 싣는다
    @EntityGraph(attributePaths = {"room", "room.author"})
    List<QuizRoomWeeklyBest> findAllByWeek_IdOrderByRankNoAsc(Long weekId);
}
