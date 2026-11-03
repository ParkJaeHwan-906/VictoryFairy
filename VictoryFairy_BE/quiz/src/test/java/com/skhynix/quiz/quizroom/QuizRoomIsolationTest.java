package com.skhynix.quiz.quizroom;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomAnswer;
import com.skhynix.domain.quizroom.entity.QuizRoomLike;
import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;
import com.skhynix.quiz.quiz.service.QuizLikeService;
import com.skhynix.quiz.quiz.service.QuizPublishService;
import com.skhynix.quiz.quiz.service.QuizService;
import com.skhynix.quiz.quiz.service.QuizSubmitService;
import com.skhynix.quiz.quizroom.service.QuizRoomAnswerService;
import com.skhynix.quiz.quizroom.service.QuizRoomLikeService;
import com.skhynix.quiz.quizroom.service.QuizRoomService;
import com.skhynix.quiz.quizroom.service.QuizRoomSubscriptionService;
import jakarta.persistence.Table;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기존 오늘의 퀴즈 경로와의 격리(H절). 실제 편성 잡·/today 응답·Redis 키 스캔은 DB/Redis 가 있어야 볼 수 있으므로,
 * 여기서는 격리를 만드는 <b>구조적 조건</b>(테이블 분리, 협력자 비공유)이 깨지지 않는지를 고정한다.
 * 이 조건이 깨지면 퀴즈방 문항이 편성 풀·point/bq·투표 집계 키에 섞일 길이 열린다.
 */
class QuizRoomIsolationTest {

    private static final List<Class<?>> QUIZ_ROOM_ENTITIES = List.of(QuizRoom.class, QuizRoomQuiz.class,
            QuizRoomQuizOption.class, QuizRoomAnswer.class, QuizRoomLike.class);

    private static List<String> dependencyNames(Class<?> type) {
        Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
                .max(java.util.Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        return Arrays.stream(constructor.getParameterTypes()).map(Class::getName).toList();
    }

    @Test
    @DisplayName("[QUIZ-CQR-100] 퀴즈방 문항 엔티티는 오늘의 퀴즈 Quiz 엔티티와 상속 관계가 아니고 다른 테이블(quiz_room_*)에 저장된다")
    void quizRoomEntities_areSeparateTablesFromDailyQuiz() {
        for (Class<?> entity : QUIZ_ROOM_ENTITIES) {
            assertThat(Quiz.class.isAssignableFrom(entity)).as(entity.getSimpleName()).isFalse();
            assertThat(entity.getAnnotation(Table.class).name()).as(entity.getSimpleName())
                    .startsWith("quiz_room")
                    .isNotIn("quizzes", "quiz_options", "quiz_users_submit", "quizzes_like");
        }
        assertThat(QuizRoomQuiz.class.getAnnotation(Table.class).name()).isEqualTo("quiz_room_quizzes");
    }

    @Test
    @DisplayName("[QUIZ-CQR-100] 매일 편성(QuizPublishService)은 퀴즈방 리포지토리·서비스를 의존하지 않는다 - 편성 풀이 퀴즈방 문항을 볼 수 없다")
    void publishService_doesNotDependOnQuizRoom() {
        assertThat(dependencyNames(QuizPublishService.class)).noneMatch(name -> name.contains("quizroom"));
    }

    @Test
    @DisplayName("[QUIZ-CQR-101] 기존 /rt/quizzes/** 서비스(조회·제출·좋아요)는 퀴즈방 리포지토리·서비스를 의존하지 않는다 - 퀴즈방 문항을 다룰 수 없다")
    void legacyQuizServices_doNotDependOnQuizRoom() throws Exception {
        Class<?> toggler = Class.forName("com.skhynix.quiz.quiz.service.QuizLikeToggler");
        for (Class<?> service : List.of(QuizService.class, QuizSubmitService.class, QuizLikeService.class,
                toggler)) {
            assertThat(dependencyNames(service)).as(service.getSimpleName())
                    .noneMatch(name -> name.contains("quizroom"));
        }
    }

    @Test
    @DisplayName("[QUIZ-CQR-62, QUIZ-CQR-102] 퀴즈방 서비스는 point·bq·quiz_users_submit·quizzes_like·Redis 투표 집계(quiz:votes:*) 협력자를 가지지 않는다")
    void quizRoomServices_doNotShareLegacyCollaborators() {
        List<String> forbidden = List.of("UserBqRepository", "QuizUserSubmitRepository", "QuizLikeRepository",
                "QuizVoteTally", "QuizRepository", "QuizOptionRepository", "QuizSubmitService",
                "QuizLikeService", "QuizService");
        for (Class<?> service : List.of(QuizRoomService.class, QuizRoomAnswerService.class,
                QuizRoomLikeService.class, QuizRoomSubscriptionService.class)) {
            assertThat(dependencyNames(service)).as(service.getSimpleName())
                    .noneMatch(name -> forbidden.stream().anyMatch(f -> name.endsWith("." + f)));
        }
    }
}
