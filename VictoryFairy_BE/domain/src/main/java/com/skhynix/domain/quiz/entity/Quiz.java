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

    /**
     * 출제 대상 구단(없을 수 있음). <b>{@code @OnDelete} 없음</b> — {@code Game → Team} 과 같은 정책이다.
     *
     * <p>여기서 구단·선수는 문제의 <b>분류 태그</b>이지 문제의 실체가 아니다. CASCADE 를 걸면
     * {@code DELETE FROM players WHERE ...} 한 줄이 그 선수의 문제 → 보기 → <b>전 사용자의 제출 기록</b>까지
     * 말없이 지운다. 제출 기록은 중복 제출 차단의 유일한 근거이기도 해서, 지워지면 이미 푼 문제를 다시
     * 풀 수 있게 된다. 문제는 사람이 쓴 콘텐츠라 수집기로 재생성되지도 않는다.
     * (domain 의 다른 CASCADE 대상은 전부 수집기가 다시 만들 수 있는 파생 데이터이거나 대상이 사라지면
     * 의미가 함께 죽는 사용자 기록이다 — 문제는 둘 다 아니다.)
     *
     * <p>non-cascade 면 대신 {@code DELETE FROM players} 가 <b>ERROR 1451 로 시끄럽게 실패</b>한다.
     * 조용한 소실보다 낫고, 실제로 앱에는 선수·구단을 지우는 경로가 없어(py-collector 도 upsert 만 한다)
     * 이 제약이 막는 건 수동 DB 작업뿐이다. 복구 불가능한 손실 vs 되돌릴 수 있는 에러의 비대칭이 근거다.
     *
     * <p>⚠ FK 의 {@code ON DELETE} 는 <b>테이블 생성 시점에만</b> 정해진다 — {@code ddl-auto=update} 는
     * 기존 FK 를 바꾸지 않으므로, 되돌리려면 {@code ALTER TABLE quizzes DROP FOREIGN KEY ... / ADD ...} 를
     * 손으로 돌아야 한다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "team_id", nullable = true)
    private Team team;

    /** 출제 대상 선수(없을 수 있음). {@code @OnDelete} 없음 — 근거는 {@link #team} 참고. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "player_id", nullable = true)
    private Player player;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    // 정답 보기 번호(QuizOption.option 과 같은 축). 보기 FK가 아니라 번호 값이다.
    // TINYINT 는 QuizOption.option 과 맞춘 것 — 같은 축의 값을 서로 비교하는데 컬럼 폭이 다르면
    // 스키마만 보고는 같은 축임이 드러나지 않는다. ddl-auto=update 는 기존 컬럼 타입을 바꾸지 않으므로
    // 테이블이 생기기 전인 지금이 아니면 수동 ALTER 가 필요하다.
    @Column(name = "answer", columnDefinition = "TINYINT", nullable = false)
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
