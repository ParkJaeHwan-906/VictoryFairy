package com.skhynix.domain.quizroom.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 퀴즈방 문항의 보기. {@code no} 는 0부터의 보기 번호({@code quiz_options.option} 과 같은 축, O/X 는 0=O·1=X).
 *
 * <p>컬럼명을 {@code option} 으로 두지 않는다 — MySQL 예약어라 백틱이 강제된다({@code QuizOption} 이 안고 있는 지뢰).
 * {@code quiz_options} 와 달리 UNIQUE 를 건다: 보기를 갈아끼우는 편집 경로가 없어 막을 이유가 없다.
 */
@Entity
@Table(name = "quiz_room_quiz_options", uniqueConstraints = {
        @UniqueConstraint(name = "uk_quiz_room_quiz_options_quiz_no", columnNames = {"quiz_id", "option_no"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRoomQuizOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_room_quiz_options_quiz"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizRoomQuiz quiz;

    @Column(name = "option_no", columnDefinition = "TINYINT", nullable = false)
    private int no;

    @Column(name = "option_text", length = 100, nullable = false)
    private String text;

    @Builder
    private QuizRoomQuizOption(QuizRoomQuiz quiz, int no, String text) {
        this.quiz = quiz;
        this.no = no;
        this.text = text;
    }
}
