package com.skhynix.domain.quizroom.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 퀴즈방 엔티티 선언이 요구사항의 <b>스키마 계약</b>(FK 삭제 정책, UNIQUE, 서수 고정, 테이블 분리)을 담는지 리플렉션으로 고정한다.
 * DB 가 없어 DDL 을 실제로 만들어 볼 수 없으므로 선언 자체가 증거다. 인덱스 선언은 쿼리 튜닝에서 바뀔 수 있어 다루지 않는다.
 * 요구사항: {@code docs/requirements/quiz/custom-quiz-room.md}.
 */
class QuizRoomMappingContractTest {

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(type.getSimpleName() + "." + name + " 필드가 없다", e);
        }
    }

    private static OnDeleteAction onDelete(Class<?> type, String name) {
        OnDelete annotation = field(type, name).getAnnotation(OnDelete.class);
        return annotation == null ? null : annotation.action();
    }

    @Test
    @DisplayName("[QUIZ-CQR-121] 퀴즈방 작성자 FK 에는 @OnDelete 가 없고 NOT NULL 이다 - 이관 없이 계정을 지우면 FK 위반으로 실패한다(fail-closed)")
    void authorForeignKey_hasNoOnDelete_andIsNotNull() {
        assertThat(onDelete(QuizRoom.class, "author")).isNull();
        assertThat(field(QuizRoom.class, "author").getAnnotation(JoinColumn.class).nullable()).isFalse();
        assertThat(field(QuizRoom.class, "author").getAnnotation(ManyToOne.class).optional()).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-122] 추천 행의 계정 FK 는 SET_NULL + nullable 이다 - 하드 삭제 뒤에도 likeCount 와 행 수가 유지된다")
    void likeAccountForeignKey_isSetNull() {
        assertThat(onDelete(QuizRoomLike.class, "userAccount")).isEqualTo(OnDeleteAction.SET_NULL);
        assertThat(field(QuizRoomLike.class, "userAccount").getAnnotation(JoinColumn.class).nullable()).isTrue();
    }

    @Test
    @DisplayName("[QUIZ-CQR-123] 답 행의 계정 FK 는 SET_NULL + nullable 이다 - 하드 삭제 뒤에도 투표 분포가 보존된다")
    void answerAccountForeignKey_isSetNull() {
        assertThat(onDelete(QuizRoomAnswer.class, "userAccount")).isEqualTo(OnDeleteAction.SET_NULL);
        assertThat(field(QuizRoomAnswer.class, "userAccount").getAnnotation(JoinColumn.class).nullable()).isTrue();
    }

    @Test
    @DisplayName("퀴즈방의 자식(문항·보기·답·추천)은 부모 삭제에 CASCADE 로 따라간다")
    void childForeignKeys_cascadeFromParent() {
        assertThat(onDelete(QuizRoomQuiz.class, "room")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(onDelete(QuizRoomQuizOption.class, "quiz")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(onDelete(QuizRoomAnswer.class, "room")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(onDelete(QuizRoomAnswer.class, "quiz")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(onDelete(QuizRoomLike.class, "room")).isEqualTo(OnDeleteAction.CASCADE);
    }

    @Test
    @DisplayName("[QUIZ-CQR-61] 답은 (계정, 문항) UNIQUE 제약이 있다 - 같은 계정의 같은 문항 동시 제출 중 하나만 남기는 마지막 심판")
    void answer_hasUniqueAccountQuizConstraint() {
        UniqueConstraint[] constraints = QuizRoomAnswer.class.getAnnotation(Table.class).uniqueConstraints();

        assertThat(constraints).hasSize(1);
        assertThat(constraints[0].columnNames()).containsExactly("user_account_id", "quiz_id");
    }

    @Test
    @DisplayName("보기는 (문항, 보기 번호) UNIQUE 이고 추천은 (계정, 방) UNIQUE 이다")
    void optionAndLike_haveUniqueConstraints() {
        UniqueConstraint[] option = QuizRoomQuizOption.class.getAnnotation(Table.class).uniqueConstraints();
        UniqueConstraint[] like = QuizRoomLike.class.getAnnotation(Table.class).uniqueConstraints();

        assertThat(option).hasSize(1);
        assertThat(option[0].columnNames()).containsExactly("quiz_id", "option_no");
        assertThat(like).hasSize(1);
        assertThat(like[0].columnNames()).containsExactlyInAnyOrder("user_account_id", "room_id");
    }

    @Test
    @DisplayName("[QUIZ-CQR-11] 문항 유형은 ORDINAL 저장이고 선언 순서가 객관식(0), O/X(1)로 고정이다 - 순서를 바꾸면 저장된 값이 뒤바뀐다")
    void quizType_isOrdinalWithFixedOrder() {
        assertThat(field(QuizRoomQuiz.class, "type").getAnnotation(Enumerated.class).value())
                .isEqualTo(EnumType.ORDINAL);
        assertThat(QuizRoomQuizType.values()).containsExactly(QuizRoomQuizType.MULTIPLE_CHOICE, QuizRoomQuizType.OX);
        assertThat(QuizRoomQuizType.MULTIPLE_CHOICE.ordinal()).isZero();
        assertThat(QuizRoomQuizType.OX.ordinal()).isEqualTo(1);
    }

    @Test
    @DisplayName("[QUIZ-CQR-19] 유형 표기는 객관식·O/X 두 값뿐이고 그 밖의 문자열·null 은 비어 있다")
    void quizType_fromLabel() {
        assertThat(QuizRoomQuizType.fromLabel("객관식")).contains(QuizRoomQuizType.MULTIPLE_CHOICE);
        assertThat(QuizRoomQuizType.fromLabel("O/X")).contains(QuizRoomQuizType.OX);
        assertThat(QuizRoomQuizType.fromLabel("주관식")).isEmpty();
        assertThat(QuizRoomQuizType.fromLabel("o/x")).isEmpty();
        assertThat(QuizRoomQuizType.fromLabel(null)).isEmpty();
        assertThat(QuizRoomQuizType.MULTIPLE_CHOICE.label()).isEqualTo("객관식");
        assertThat(QuizRoomQuizType.OX.label()).isEqualTo("O/X");
    }

    @Test
    @DisplayName("[QUIZ-CQR-100] 퀴즈방 엔티티는 전부 quiz_room 접두 테이블이고 오늘의 퀴즈 테이블(quizzes 등)을 쓰지 않는다")
    void tables_areSeparateFromDailyQuiz() {
        for (Class<?> type : Arrays.asList(QuizRoom.class, QuizRoomQuiz.class, QuizRoomQuizOption.class,
                QuizRoomAnswer.class, QuizRoomLike.class)) {
            assertThat(type.isAnnotationPresent(Entity.class)).isTrue();
            assertThat(type.getAnnotation(Table.class).name()).as(type.getSimpleName()).startsWith("quiz_room");
        }
        assertThat(QuizRoom.class.getAnnotation(Table.class).name()).isEqualTo("quiz_rooms");
    }

    @Test
    @DisplayName("[QUIZ-CQR-28] 제목·소개·문제 컬럼 길이는 요구사항 상한(50·500·200)과 같고, 보기는 100자다")
    void columnLengths_matchRequirementLimits() {
        assertThat(field(QuizRoom.class, "title").getAnnotation(Column.class).length()).isEqualTo(50);
        assertThat(field(QuizRoom.class, "description").getAnnotation(Column.class).length()).isEqualTo(500);
        assertThat(field(QuizRoomQuiz.class, "question").getAnnotation(Column.class).length()).isEqualTo(200);
        assertThat(field(QuizRoomQuizOption.class, "text").getAnnotation(Column.class).length()).isEqualTo(100);
    }

    // ---- 주간 베스트 (개정 1차) ----

    private static java.util.Set<java.util.List<String>> uniqueColumns(Class<?> type) {
        return Arrays.stream(type.getAnnotation(Table.class).uniqueConstraints())
                .map(c -> java.util.List.of(c.columnNames())).collect(java.util.stream.Collectors.toSet());
    }

    private static java.util.Set<String> uniqueNames(Class<?> type) {
        return Arrays.stream(type.getAnnotation(Table.class).uniqueConstraints())
                .map(UniqueConstraint::name).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("[QUIZ-CQR-143] 주 행은 week_start UNIQUE 다 - 다중 인스턴스가 같은 주를 동시에 확정할 때 하나만 남기는 심판")
    void week_hasUniqueWeekStart() {
        assertThat(QuizRoomWeek.class.getAnnotation(Table.class).name()).isEqualTo("quiz_room_weeks");
        assertThat(uniqueColumns(QuizRoomWeek.class)).containsExactly(java.util.List.of("week_start"));
        assertThat(uniqueNames(QuizRoomWeek.class)).containsExactly("uk_quiz_room_weeks_week_start");
    }

    @Test
    @DisplayName("[QUIZ-CQR-143, QUIZ-CQR-137] 확정 순위는 (주, 방) 과 (주, 순위) 가 각각 UNIQUE 다 - 한 방에 지급이 두 번 나가거나 한 주에 순위가 겹칠 수 없다")
    void weeklyBest_hasUniqueWeekRoomAndWeekRank() {
        assertThat(QuizRoomWeeklyBest.class.getAnnotation(Table.class).name()).isEqualTo("quiz_room_weekly_bests");
        assertThat(uniqueColumns(QuizRoomWeeklyBest.class)).containsExactlyInAnyOrder(
                java.util.List.of("week_id", "room_id"), java.util.List.of("week_id", "rank_no"));
    }

    @Test
    @DisplayName("[QUIZ-CQR-142, QUIZ-CQR-148] 지급 계정 FK 는 SET_NULL + nullable 이다 - 하드 삭제 뒤에도 확정 순위가 남고, 미지급 순위는 계정이 NULL 이다")
    void weeklyBestRewardedAccount_isSetNullAndNullable() {
        assertThat(onDelete(QuizRoomWeeklyBest.class, "rewardedAccount")).isEqualTo(OnDeleteAction.SET_NULL);
        JoinColumn column = field(QuizRoomWeeklyBest.class, "rewardedAccount").getAnnotation(JoinColumn.class);
        assertThat(column.name()).isEqualTo("rewarded_account_id");
        assertThat(column.nullable()).isTrue();
        assertThat(field(QuizRoomWeeklyBest.class, "rewardedAccount").getAnnotation(ManyToOne.class).optional()).isTrue();
    }

    @Test
    @DisplayName("[QUIZ-CQR-148] 순위 행의 주·방 FK 는 NOT NULL + CASCADE 다 - 방이 지워져야만 순위 행이 같이 사라진다 (계정 삭제로는 사라지지 않는다)")
    void weeklyBestWeekAndRoom_areNotNullCascade() {
        assertThat(onDelete(QuizRoomWeeklyBest.class, "week")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(onDelete(QuizRoomWeeklyBest.class, "room")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(field(QuizRoomWeeklyBest.class, "week").getAnnotation(JoinColumn.class).nullable()).isFalse();
        assertThat(field(QuizRoomWeeklyBest.class, "room").getAnnotation(JoinColumn.class).nullable()).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-148] 확정 뒤 바뀌지 않도록 주 행의 week_start·confirmed_at 은 updatable=false 다")
    void week_columnsAreNotUpdatable() {
        assertThat(field(QuizRoomWeek.class, "weekStart").getAnnotation(Column.class).updatable()).isFalse();
        assertThat(field(QuizRoomWeek.class, "confirmedAt").getAnnotation(Column.class).updatable()).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-134] 추천·답의 createdAt 은 호출자가 넣는 값이다 - @CreationTimestamp(JVM 기본 존=UTC)로 찍으면 KST 월요일 경계가 9시간 어긋난다")
    void createdAt_isNotCreationTimestamp() {
        assertThat(field(QuizRoomLike.class, "createdAt").isAnnotationPresent(org.hibernate.annotations.CreationTimestamp.class)).isFalse();
        assertThat(field(QuizRoomAnswer.class, "createdAt").isAnnotationPresent(org.hibernate.annotations.CreationTimestamp.class)).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-100] 주간 베스트 엔티티도 quiz_room 접두 테이블이다")
    void weeklyTables_areQuizRoomPrefixed() {
        for (Class<?> type : Arrays.asList(QuizRoomWeek.class, QuizRoomWeeklyBest.class)) {
            assertThat(type.getAnnotation(Table.class).name()).as(type.getSimpleName()).startsWith("quiz_room");
        }
    }

    // ---- 엔티티 동작 ----

    private static QuizRoom newRoom() {
        UserAccount author = UserAccount.builder().nickname("작성자").password("password1!").build();
        return QuizRoom.builder().author(author).title("t").description("d").quizCount(3)
                .createdAt(LocalDateTime.of(2026, 10, 14, 12, 0)).build();
    }

    @Test
    @DisplayName("[QUIZ-CQR-27] 새 퀴즈방은 likeCount 0 으로 시작하고 builder 값을 그대로 담는다")
    void newRoom_startsWithZeroLikes() {
        QuizRoom room = newRoom();

        assertThat(room.getLikeCount()).isZero();
        assertThat(room.getQuizCount()).isEqualTo(3);
        assertThat(room.getTitle()).isEqualTo("t");
        assertThat(room.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 14, 12, 0));
    }

    @Test
    @DisplayName("[QUIZ-CQR-72] 추천 수는 올리고 내릴 수 있으나 0 아래로 내려가지 않는다")
    void likeCount_increaseAndDecrease_neverNegative() {
        QuizRoom room = newRoom();

        room.increaseLikeCount();
        room.increaseLikeCount();
        room.decreaseLikeCount();
        assertThat(room.getLikeCount()).isEqualTo(1L);

        room.decreaseLikeCount();
        room.decreaseLikeCount();
        assertThat(room.getLikeCount()).isZero();
    }
}
