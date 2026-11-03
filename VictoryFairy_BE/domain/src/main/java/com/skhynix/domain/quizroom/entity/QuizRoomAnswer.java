package com.skhynix.domain.quizroom.entity;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 퀴즈방 문항 하나에 대한 한 계정의 답. 계정당 문항 1회(재풀이 없음)라 갱신 없는 기록성 행이다.
 *
 * <p>{@code room_id} 는 {@code quiz.room} 으로 알 수 있지만 따로 둔다 — "이 계정이 이 방에서 몇 개 답했나"와
 * 방 단위 분포 집계가 조인 없이 끝난다.
 *
 * <p>계정 FK 가 SET NULL 인 이유는 {@code quizzes_like} 와 같다 — 하드 삭제 뒤에도 투표 분포는 보존돼야 한다.
 * NULL 은 UNIQUE 에서 서로 다른 값이라 탈퇴자가 몇 명이든 충돌하지 않는다.
 */
// uk_quiz_room_answers_account_quiz: 같은 계정의 같은 문항 동시 제출 2건 중 하나만 남게 DB 가 심판한다.
//
// 아래 세 인덱스는 모두 커버링이다 — 각 질의가 필요한 컬럼이 인덱스 안에 다 있어 클러스터드 인덱스(행)를 읽지 않는다.
// 앞의 둘은 FK 자동 인덱스 자리를 대신한다: 선행 컬럼이 FK 컬럼이라 InnoDB 가 room_id·quiz_id 단독 인덱스를 따로
// 만들지 않으므로(이미 있으면 MySQL 이 조용히 지운다) 인덱스 개수는 늘지 않고 뒤에 컬럼만 붙는다. (미측정, 제거하지 말 것)
//
// idx_quiz_room_answers_room_quiz_option(room_id, quiz_id, option_no): countVotesByRoomId 의
// "WHERE room_id = ? GROUP BY quiz_id, option_no" 가 인덱스 순서대로 읽으며 묶여 임시 테이블·정렬이 없다.
// room_id 단독(FK 자동) 인덱스였다면 그 방의 답 행마다 PK 로 행을 다시 읽는다 — 인기 방일수록 답이 많다.
// 순서 근거: room_id 는 등치 조건이라 맨 앞, 이어서 GROUP BY 순서 그대로.
//
// idx_quiz_room_answers_quiz_option(quiz_id, option_no): 답 제출마다 도는 countVotesByQuizId 를 같은 방식으로 받는다.
//
// idx_quiz_room_answers_account_room(user_account_id, room_id): 답 제출마다 도는 countByUserAccount_IdAndRoom_Id 와
// 결과 생성의 findAllByUserAccount_IdAndRoom_Id 용. uk_..._account_quiz 로 진입하면 선행 컬럼만 맞아 "그 계정이 모든 방에
// 낸 답"을 훑고 행마다 room_id 를 확인한다 — 스캔 길이가 그 계정의 누적 활동량에 비례한다. 이 인덱스는 방당 최대 20건.
// 순서 근거: 두 질의 모두 두 컬럼이 등치라 어느 순서든 같은 범위를 읽는다. "계정의 답" 질의라 uk 와 같은 축으로 맞췄을 뿐이다.
//
// 이름·컬럼은 infra/sql/migrate-quiz-room.sql 과 문자 그대로 같아야 한다 — 다르면 user 의 ddl-auto=update 가 하나 더 만든다.
@Entity
@Table(name = "quiz_room_answers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_quiz_room_answers_account_quiz",
                columnNames = {"user_account_id", "quiz_id"})
}, indexes = {
        @Index(name = "idx_quiz_room_answers_room_quiz_option", columnList = "room_id, quiz_id, option_no"),
        @Index(name = "idx_quiz_room_answers_quiz_option", columnList = "quiz_id, option_no"),
        @Index(name = "idx_quiz_room_answers_account_room", columnList = "user_account_id, room_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRoomAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_account_id",
            foreignKey = @ForeignKey(name = "fk_quiz_room_answers_account"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private UserAccount userAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_room_answers_room"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizRoom room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_room_answers_quiz"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizRoomQuiz quiz;

    @Column(name = "option_no", columnDefinition = "TINYINT", nullable = false)
    private int optionNo;

    // 호출자의 Clock(KST) 값. 주간 점수가 이 값을 KST 월요일 0시 경계와 비교한다 — @CreationTimestamp 는 JVM 기본 존
    // (파드 UTC)으로 찍혀 경계가 9시간 어긋난다
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private QuizRoomAnswer(UserAccount userAccount, QuizRoom room, QuizRoomQuiz quiz, int optionNo,
            LocalDateTime createdAt) {
        this.userAccount = userAccount;
        this.room = room;
        this.quiz = quiz;
        this.optionNo = optionNo;
        this.createdAt = createdAt;
    }
}
