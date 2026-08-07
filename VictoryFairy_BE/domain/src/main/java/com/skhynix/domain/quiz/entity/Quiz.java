package com.skhynix.domain.quiz.entity;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.team.entity.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 문제 한 건. 보기는 {@link QuizOption}이 {@code quiz_id} FK로 들고 있고, 제출 기록은
 * {@link QuizUserSubmit}이 갖는다.
 *
 * <p><b>출제 대상은 {@code team}/{@code player} 두 FK의 조합으로 표현한다</b>(사용자 확정):
 * <table border="1">
 *   <caption>대상 판정</caption>
 *   <tr><th>team</th><th>player</th><th>의미</th><th>판정 메서드</th></tr>
 *   <tr><td>not null</td><td>not null</td><td>특정 선수에 대한 문제</td><td>{@link #isPlayerQuiz()}</td></tr>
 *   <tr><td>not null</td><td>null</td><td>특정 구단에 대한 문제</td><td>{@link #isTeamQuiz()}</td></tr>
 *   <tr><td>null</td><td>null</td><td>야구 도메인 자체의 문제</td><td>{@link #isGeneralQuiz()}</td></tr>
 * </table>
 * 그래서 두 FK는 <b>nullable</b>이다. 사용자가 제공한 DDL 초안에는 둘 다 {@code NOT NULL}로 적혀
 * 있었으나, 그대로 두면 위 2·3행이 애초에 저장 불가능해 대상 구분 설계 자체가 성립하지 않는다.
 *
 * <p>⚠ <b>{@code team == null && player != null}은 정의되지 않은 조합이다</b>(선수는 반드시 소속 구단이
 * 있다). 스키마로는 막을 수 없어 — MySQL CHECK 제약을 걸면 선수의 구단 이적 시 갱신 순서에 따라 걸린다 —
 * <b>쓰기 경로가 지켜야 하는 서비스 정책</b>이다. 이 조합이 저장되면 {@link #isPlayerQuiz()}만 true가 되어
 * 구단 정보 없는 선수 문제로 취급된다. {@code UserSupportTeam}의 "한 사용자는 구단 1개만 응원한다"와 같은
 * 성격의 제약이다.
 *
 * <p>{@code score}(난이도 가중 점수)는 <b>MVP 이후 작업</b>이라 지금은 채워지지 않는다 — 그래서 nullable
 * 이며, 소비하는 쪽은 null을 반드시 다뤄야 한다. 값이 항상 들어오게 되면 그때 non-null로 좁힌다.
 *
 * <p>{@code answer}는 정답 보기의 번호로, {@link QuizOption#getOption()}과 같은 축이다
 * (O/X는 0=X, 1=O). 보기 엔티티를 가리키는 FK가 아니라 <b>번호 값</b>인 것에 주의 — 보기 행이 재생성돼도
 * 번호만 유지되면 정답이 따라 깨지지 않는다.
 */
@Entity
@Table(name = "quizzes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Quiz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // 코드 테이블이라 non-cascade — 유형 행이 지워져도 문제는 보존한다(Game → GameStatus 와 같은 정책)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_type_id", nullable = false)
    private QuizType quizType;

    // CASCADE — 특정 구단에 대한 문제는 그 구단이 사라지면 함께 사라져도 된다
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "team_id", nullable = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team team;

    // CASCADE — 특정 선수에 대한 문제는 그 선수가 사라지면 함께 사라져도 된다
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "player_id", nullable = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Player player;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    // 정답 보기 번호(QuizOption.option 과 같은 축). 보기 FK가 아니라 번호 값이다.
    @Column(name = "answer", nullable = false)
    private Integer answer;

    // 난이도 가중 점수 — MVP 이후 도입이라 지금은 null
    @Column(name = "score")
    private Double score;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Quiz(QuizType quizType, Team team, Player player, String content, Integer answer,
            Double score) {
        this.quizType = quizType;
        this.team = team;
        this.player = player;
        this.content = content;
        this.answer = answer;
        this.score = score;
    }

    /** 특정 선수에 대한 문제인가. */
    public boolean isPlayerQuiz() {
        return player != null;
    }

    /** 특정 구단에 대한 문제인가(선수 지정 없음). */
    public boolean isTeamQuiz() {
        return team != null && player == null;
    }

    /** 구단·선수를 가리지 않는 야구 도메인 자체의 문제인가. */
    public boolean isGeneralQuiz() {
        return team == null && player == null;
    }
}
