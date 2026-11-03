package com.skhynix.quiz.quizroom.dto;

import com.skhynix.domain.quizroom.entity.QuizRoom;
import java.time.LocalDateTime;

public record RoomSummaryResponse(
        Long roomId,
        String title,
        String description,
        AuthorResponse author,
        int quizCount,
        long likeCount,
        long solvingCount,
        LocalDateTime createdAt) {

    public static RoomSummaryResponse of(QuizRoom room, long solvingCount) {
        return new RoomSummaryResponse(room.getId(), room.getTitle(), room.getDescription(),
                AuthorResponse.from(room.getAuthor()), room.getQuizCount(), room.getLikeCount(),
                solvingCount, room.getCreatedAt());
    }
}
