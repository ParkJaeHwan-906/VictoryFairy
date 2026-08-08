package com.skhynix.domain.quiz.entity;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.team.entity.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
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
 * <p><b>출제 대상은 FK 4개({@code team}/{@code opponentTeam}/{@code player}/{@code game})의 조합으로
 * 표현한다.</b> 대상 분류를 별도 컬럼에 저장하지 않는 이유는 조합에서 전부 파생되기 때문이다 — 같은
 * 사실을 두 곳에 두면 반드시 어긋난다.
 * <table border="1">
 *   <caption>대상 판정</caption>
 *   <tr><th>조합</th><th>의미</th><th>판정 메서드</th></tr>
 *   <tr><td>player ≠ null</td><td>특정 선수에 대한 문제</td><td>{@link #isPlayerQuiz()}</td></tr>
 *   <tr><td>team ≠ null, opponentTeam = null, player = null</td><td>특정 구단에 대한 문제</td>
 *       <td>{@link #isTeamQuiz()}</td></tr>
 *   <tr><td>team ≠ null, opponentTeam ≠ null</td><td>두 구단 맞대결에 대한 문제</td>
 *       <td>{@link #isMatchupQuiz()}</td></tr>
 *   <tr><td>game ≠ null</td><td>특정 경기에 대한 문제</td><td>{@link #isGameQuiz()}</td></tr>
 *   <tr><td>전부 null</td><td>야구 도메인 자체의 문제</td><td>{@link #isGeneralQuiz()}</td></tr>
 * </table>
 * 실데이터(quiz-candidates 2026-08-07 실측)가 원래의 3분류(선수/구단/일반)를 벗어났다 — "한화 vs KT
 * 상대전적"(구단 2개), "8/4 LG-SSG 승리투수"(특정 경기)가 이미 출제되고 있어 {@code opponentTeam}과
 * {@code game}을 추가했다.
 *
 * <p><b>불변식(쓰기 경로가 지켜야 하는 서비스 정책 — 스키마로는 막지 않는다)</b>:
 * <ul>
 *   <li>{@code opponentTeam != null}이면 {@code team != null}이다(상대만 있는 맞대결은 없다).</li>
 *   <li>{@code game != null}이면 로더가 그 경기의 홈/원정으로 {@code team}(홈)·{@code opponentTeam}
 *       (원정)을 함께 채운다 — "내 응원팀 관련 문제" 조회가 경기 문항까지 조인 없이
 *       {@code team_id = ? OR opponent_team_id = ?} 한 줄로 나오게 하기 위해서다.</li>
 *   <li>{@code team == null && player != null}은 <b>정상 조합이다</b> — 원래 미정의로 뒀으나
 *       정답 유출 방지 규칙이 이를 뒤집었다: 대상 FK 에는 문제가 <i>전제</i>하는 엔티티만 담고
 *       <b>정답에만 등장하는 엔티티는 담지 않는다</b>. "강백호가 FA로 새로 합류한 팀은?"이 그
 *       예다 — 강백호(전제)는 담기지만 소속팀(정답)을 담으면 팀 필터가 곧 정답 힌트가 되므로
 *       {@code team}을 일부러 비운다. 로더가 {@code player.team}으로 채워 넣어서도 안 된다.</li>
 * </ul>
 *
 * <p>{@code externalId}는 AI 파이프라인 산출물(S3 {@code quiz-candidates/{date}/{quizId}.json})의
 * {@code quizId}다. <b>적재 멱등성의 열쇠</b>이며(재실행·파드 동시 실행 시 UNIQUE 가 중복을 원자적으로
 * 차단), S3 원본(근거 {@code evidence} 포함)으로의 역추적 링크이기도 하다 — {@code games.naverGameId}와
 * 같은 계열(외부 생산자의 식별자를 우리 행에 보관해야 대조가 성립한다). 사람이 직접 쓰는 퀴즈는 null.
 *
 * <p>{@code score}는 문제 배점이다. 원래 "MVP 이후"로 비워뒀으나 파이프라인이 {@code pointReward}
 * (난이도 연동, scoring.yaml이 정본)를 이미 보내고 있어 적재 시 채운다. 사람이 쓴 퀴즈는 null일 수
 * 있으므로 소비하는 쪽은 여전히 null을 다뤄야 한다.
 *
 * <p>{@code answer}는 정답 보기의 번호로, {@link QuizOption#getOption()}과 같은 축이다
 * (O/X는 0=O, 1=X — 후보 JSON의 A=0, B=1 순번 그대로). 보기 엔티티를 가리키는 FK가 아니라 <b>번호
 * 값</b>인 것에 주의 — 보기 행이 재생성돼도 번호만 유지되면 정답이 따라 깨지지 않는다.
 */
@Entity
@Table(
        name = "quizzes",
        uniqueConstraints = @UniqueConstraint(name = "uk_quizzes_external_id",
                columnNames = "external_id"),
        indexes = @Index(name = "idx_quizzes_quiz_date", columnList = "quiz_date"))
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

    /**
     * 맞대결 문제의 상대 구단(없을 수 있음). {@code @OnDelete} 없음 — 근거는 {@link #team} 참고.
     *
     * <p>이름이 "홈/원정"이 아닌 이유: 맞대결 문제 대부분("올해 한화는 KT 상대 우위다?" 같은 시즌
     * 상대전적)에는 홈/원정 개념 자체가 없다. 홈은 <b>사실 주장</b>이라 아무 데나 넣으면 컬럼이 거짓을
     * 말하게 되지만, 상대는 순서가 임의여도 관계가 성립한다. 경기 문항({@code game != null})만 예외적으로
     * 홈=team, 원정=opponentTeam 규약을 따른다(로더가 채움 — 클래스 javadoc 불변식 참고).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "opponent_team_id", nullable = true)
    private Team opponentTeam;

    /** 출제 대상 선수(없을 수 있음). {@code @OnDelete} 없음 — 근거는 {@link #team} 참고. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "player_id", nullable = true)
    private Player player;

    /**
     * 출제 대상 경기(없을 수 있음). {@code @OnDelete} 없음 — 근거는 {@link #team} 참고.
     * 예측(PREDICTION) 퀴즈가 도입되면 경기 종료 후 정산이 이 FK 를 딛고 이뤄진다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "game_id", nullable = true)
    private Game game;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    // 정답 보기 번호(QuizOption.option 과 같은 축). 보기 FK가 아니라 번호 값이다.
    // TINYINT 는 QuizOption.option 과 맞춘 것 — 같은 축의 값을 서로 비교하는데 컬럼 폭이 다르면
    // 스키마만 보고는 같은 축임이 드러나지 않는다.
    @Column(name = "answer", columnDefinition = "TINYINT", nullable = false)
    private Integer answer;

    // 배점 — AI 산출물의 pointReward(난이도 연동). 사람이 쓴 퀴즈는 null 가능(클래스 javadoc 참고)
    @Column(name = "score", nullable = true)
    private Double score;

    /**
     * AI 파이프라인 산출물의 {@code quizId}(예: {@code QZ-20260807-001}). 적재 멱등키이자 S3 원본
     * 역추적 링크 — 클래스 javadoc 참고. 사람이 직접 쓰는 퀴즈는 null이며, MySQL 은 UNIQUE 인덱스에서
     * NULL 중복을 허용하므로 충돌하지 않는다.
     */
    @Column(name = "external_id", length = 32, nullable = true)
    private String externalId;

    /**
     * <b>출제일</b>(생성일이 아니다). "오늘의 퀴즈" 조회({@code WHERE quiz_date = ?})의 유일한
     * 기준이다. <b>NULL 은 미편성 풀 대기</b> — 역대기록형(시효성 없는) 문제는 생성일에 묶일 이유가
     * 없어 풀에 쌓이고, 매일 편성 잡({@code QuizPublishService})이 그날 세트의 부족분만큼 날짜를
     * 스탬프한다. 세트가 사용자별이 아니라 날짜별인 이유는 레이팅 공정성 — 전원이 같은 문제를 받아야
     * 점수 비교가 성립한다. 경기 문항(시효성)만 적재 시점에 바로 날짜가 찍힌다
     * ({@code QuizIngestService}). 생성일 추적은 {@code externalId}(QZ-YYYYMMDD-###)와
     * {@code createdAt}이 담당하므로 이 컬럼이 겸할 필요가 없다.
     */
    @Column(name = "quiz_date", nullable = true)
    private LocalDate quizDate;

    // UI 난이도 배지용(EASY/MEDIUM/HARD/EXPERT — 파이프라인 계약값 그대로). 사람이 쓴 퀴즈는 null 가능
    @Column(name = "difficulty", length = 10, nullable = true)
    private String difficulty;

    /**
     * 출제 템플릿 식별자(예: {@code MEME_ORIGIN}). 스펙이 예고한 피드백 루프 — 템플릿별 유저 반응
     * (정답률·스킵률)을 집계해 출제 가중치를 조정 — 의 조인 키다. 지금은 기록만 한다.
     */
    @Column(name = "template_id", length = 64, nullable = true)
    private String templateId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Quiz(QuizType quizType, Team team, Team opponentTeam, Player player, Game game,
            String content, Integer answer, Double score, String externalId, LocalDate quizDate,
            String difficulty, String templateId) {
        this.quizType = quizType;
        this.team = team;
        this.opponentTeam = opponentTeam;
        this.player = player;
        this.game = game;
        this.content = content;
        this.answer = answer;
        this.score = score;
        this.externalId = externalId;
        this.quizDate = quizDate;
        this.difficulty = difficulty;
        this.templateId = templateId;
    }

    /** 특정 선수에 대한 문제인가. */
    public boolean isPlayerQuiz() {
        return player != null;
    }

    /** 특정 구단 하나에 대한 문제인가(선수·상대 구단 지정 없음). */
    public boolean isTeamQuiz() {
        return team != null && opponentTeam == null && player == null;
    }

    /** 두 구단의 맞대결에 대한 문제인가. */
    public boolean isMatchupQuiz() {
        return team != null && opponentTeam != null;
    }

    /** 특정 경기에 대한 문제인가. */
    public boolean isGameQuiz() {
        return game != null;
    }

    /** 구단·선수·경기를 가리지 않는 야구 도메인 자체의 문제인가. */
    public boolean isGeneralQuiz() {
        return team == null && player == null && game == null;
    }
}
