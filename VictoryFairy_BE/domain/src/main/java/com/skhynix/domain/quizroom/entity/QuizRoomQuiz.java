package com.skhynix.domain.quizroom.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 퀴즈방의 문항 하나. 표시 순서는 {@code id} 오름차순이다 — 생성 요청 하나가 요청 배열 순서대로 IDENTITY INSERT 를
 * 하므로 id 순서가 곧 요청 순서다(수정이 없어 순서가 바뀔 일도 없다).
 *
 * <p>{@code answer} 는 보기 번호 값이다(FK 가 아니다 — {@code quizzes.answer} 와 같은 판단).
 */
@Entity
@Table(name = "quiz_room_quizzes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRoomQuiz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_room_quizzes_room"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizRoom room;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "type", columnDefinition = "TINYINT", nullable = false)
    private QuizRoomQuizType type;

    @Column(name = "question", length = 200, nullable = false)
    private String question;

    @Column(name = "answer", columnDefinition = "TINYINT", nullable = false)
    private int answer;

    @Builder
    private QuizRoomQuiz(QuizRoom room, QuizRoomQuizType type, String question, int answer) {
        this.room = room;
        this.type = type;
        this.question = question;
        this.answer = answer;
    }
}
