package com.skhynix.quiz.quizroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skhynix.domain.quizroom.entity.QuizRoom;
import java.time.LocalDateTime;

public record RoomDetailResponse(
        Long roomId,
        String title,
        String description,
        AuthorResponse author,
        int quizCount,
        long likeCount,
        long solvingCount,
        LocalDateTime createdAt,
        boolean liked,
        // is 접두 boolean 은 게터 규약으로 "author" 로 읽힐 수 있어 키를 못 박는다(같은 이름의 author 키와 충돌)
        @JsonProperty("isAuthor") boolean isAuthor) {

    public static RoomDetailResponse of(QuizRoom room, long solvingCount, boolean liked, boolean isAuthor) {
        return new RoomDetailResponse(room.getId(), room.getTitle(), room.getDescription(),
                AuthorResponse.from(room.getAuthor()), room.getQuizCount(), room.getLikeCount(),
                solvingCount, room.getCreatedAt(), liked, isAuthor);
    }
}
