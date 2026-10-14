package com.skhynix.domain.quizroom.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomAnswer;
import com.skhynix.domain.quizroom.entity.QuizRoomLike;
import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;
import com.skhynix.domain.quizroom.entity.QuizRoomWeek;
import com.skhynix.domain.quizroom.entity.QuizRoomWeeklyBest;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 퀴즈방 리포지토리 {@code @Query} 의 HQL 구문·매핑 검증 - DB 없이 Hibernate 메타모델만 올려 파싱·의미 분석을 통과시킨다.
 * (커뮤니티 {@code CommunityRepositoryQueryTest} 와 같은 방식.)
 *
 * <p>⚠ {@code @DataJpaTest} 를 대신하지 않는다. 쿼리 <b>결과</b>(투표 집계 값, 정렬, 락 동작, 이관 건수)는 실제 DB 가
 * 있어야 검증할 수 있고 이 저장소에는 H2/Testcontainers 가 없다(도입 결정 필요). 쿼리 본문은 튜닝으로 바뀔 수 있어
 * 글자 단위로 고정하지 않고, 컴파일 가능 여부와 시그니처 계약(락·수정 쿼리 표식)만 본다.
 */
class QuizRoomRepositoryQueryTest {

    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;

    @BeforeAll
    static void bootMetamodelWithoutDatabase() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        MetadataSources sources = new MetadataSources(registry)
                .addAnnotatedClass(User.class)
                .addAnnotatedClass(UserAccount.class)
                .addAnnotatedClass(Team.class)
                .addAnnotatedClass(QuizRoom.class)
                .addAnnotatedClass(QuizRoomQuiz.class)
                .addAnnotatedClass(QuizRoomQuizOption.class)
                .addAnnotatedClass(QuizRoomAnswer.class)
                .addAnnotatedClass(QuizRoomLike.class)
                .addAnnotatedClass(QuizRoomWeek.class)
                .addAnnotatedClass(QuizRoomWeeklyBest.class);
        sessionFactory = sources.buildMetadata().buildSessionFactory();
    }

    @AfterAll
    static void shutdown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static List<Class<?>> repositories() {
        return List.of(QuizRoomRepository.class, QuizRoomQuizRepository.class, QuizRoomQuizOptionRepository.class,
                QuizRoomAnswerRepository.class, QuizRoomLikeRepository.class, QuizRoomWeekRepository.class,
                QuizRoomWeeklyBestRepository.class);
    }

    private static Method method(Class<?> repository, String name) {
        for (Method m : repository.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError(repository.getSimpleName() + "." + name + " 이 없다");
    }

    @Test
    @DisplayName("퀴즈방 리포지토리의 모든 @Query(및 countQuery)가 엔티티 메타모델로 파싱·의미 분석을 통과한다 - 프로퍼티명 오타 시 기동 불가 사고 방지")
    void everyAnnotatedQuery_compilesAgainstMetamodel() {
        List<String> checked = new ArrayList<>();
        try (Session session = sessionFactory.openSession()) {
            for (Class<?> repository : repositories()) {
                for (Method method : repository.getDeclaredMethods()) {
                    Query query = method.getAnnotation(Query.class);
                    if (query == null) {
                        continue;
                    }
                    session.createQuery(query.value());
                    checked.add(repository.getSimpleName() + "." + method.getName());
                    if (!query.countQuery().isEmpty()) {
                        session.createQuery(query.countQuery());
                        checked.add(repository.getSimpleName() + "." + method.getName() + "#count");
                    }
                }
            }
        }
        assertThat(checked).contains(
                "QuizRoomRepository.findWithLockById",
                "QuizRoomRepository.reassignAuthor",
                "QuizRoomAnswerRepository.countVotesByRoomId",
                "QuizRoomAnswerRepository.countVotesByQuizId",
                "QuizRoomLikeRepository.deleteByUserAccountIdAndRoomId",
                "QuizRoomLikeRepository.countWeeklyLikesByRoom",
                "QuizRoomAnswerRepository.countWeeklyNewSolversByRoom");
    }

    @Test
    @DisplayName("[QUIZ-CQR-75] findWithLockById 는 PESSIMISTIC_WRITE 락 조회다 - 같은 방의 추천 갱신을 직렬화하는 장치")
    void findWithLockById_isPessimisticWrite() {
        Lock lock = method(QuizRoomRepository.class, "findWithLockById").getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("[QUIZ-CQR-121] reassignAuthor 는 수정 쿼리(@Modifying)이고 이관된 방 수(int)를 반환한다")
    void reassignAuthor_isModifyingReturningCount() {
        Method reassign = method(QuizRoomRepository.class, "reassignAuthor");

        assertThat(reassign.isAnnotationPresent(Modifying.class)).isTrue();
        assertThat(reassign.getReturnType()).isEqualTo(int.class);
        assertThat(reassign.getParameterTypes()).containsExactly(Long.class, UserAccount.class);
    }

    @Test
    @DisplayName("[QUIZ-CQR-71] 추천 삭제는 수정 쿼리이고 지운 행 수(int)를 반환한다")
    void deleteLike_isModifyingReturningCount() {
        Method delete = method(QuizRoomLikeRepository.class, "deleteByUserAccountIdAndRoomId");

        assertThat(delete.isAnnotationPresent(Modifying.class)).isTrue();
        assertThat(delete.getReturnType()).isEqualTo(int.class);
    }

    @Test
    @DisplayName("[QUIZ-CQR-123] 투표 분포 집계 쿼리는 계정 축을 조건·그룹에 쓰지 않는다 - 소유자가 비워진 답도 그대로 센다")
    void voteCountQueries_doNotUseAccountColumn() {
        for (String name : List.of("countVotesByRoomId", "countVotesByQuizId")) {
            String hql = method(QuizRoomAnswerRepository.class, name).getAnnotation(Query.class).value();

            assertThat(hql).as(name).doesNotContain("userAccount");
            assertThat(hql).as(name).contains("group by");
        }
    }

    @Test
    @DisplayName("[QUIZ-CQR-45] 조회용 리포지토리 메서드에는 @Modifying 이 없다 (조회가 행을 바꾸지 않는다)")
    void readMethods_areNotModifying() {
        for (String name : List.of("findAllByOrderByIdDesc", "findWithAuthorById")) {
            assertThat(method(QuizRoomRepository.class, name).isAnnotationPresent(Modifying.class)).as(name).isFalse();
        }
    }

    @Test
    @DisplayName("[QUIZ-CQR-33 폐기] 실시간 인기 후보 쿼리(findTop5..., findAllByIdIn)는 리포지토리에서 제거돼 있다")
    void realtimePopularQueries_areRemoved() {
        List<String> names = java.util.Arrays.stream(QuizRoomRepository.class.getDeclaredMethods())
                .map(Method::getName).toList();

        assertThat(names).doesNotContain("findTop5ByOrderByLikeCountDescIdDesc", "findAllByIdIn");
    }

    @Test
    @DisplayName("[QUIZ-CQR-134] 주간 집계 쿼리는 [start, end) 두 시각을 받아 방별 집계(QuizRoomCountView) 목록을 반환하고 조회 전용이다")
    void weeklyCountQueries_takeHalfOpenRangeAndReturnCountViews() throws NoSuchMethodException {
        Method likes = method(QuizRoomLikeRepository.class, "countWeeklyLikesByRoom");
        Method solvers = method(QuizRoomAnswerRepository.class, "countWeeklyNewSolversByRoom");

        for (Method m : List.of(likes, solvers)) {
            assertThat(m.getParameterTypes()).as(m.getName())
                    .containsExactly(java.time.LocalDateTime.class, java.time.LocalDateTime.class);
            assertThat(m.getGenericReturnType().getTypeName()).as(m.getName()).contains("QuizRoomCountView");
            assertThat(m.isAnnotationPresent(Modifying.class)).as(m.getName()).isFalse();
        }
    }

    @Test
    @DisplayName("[QUIZ-CQR-135] 주간 집계 쿼리는 방 작성자를 비교 대상으로 삼는다 (작성자 본인의 추천·풀이를 점수에서 뺀다) - 질의 본문 전체가 아니라 작성자 참조 여부만 본다")
    void weeklyCountQueries_referToRoomAuthor() {
        for (Method m : List.of(method(QuizRoomLikeRepository.class, "countWeeklyLikesByRoom"),
                method(QuizRoomAnswerRepository.class, "countWeeklyNewSolversByRoom"))) {
            assertThat(m.getAnnotation(Query.class).value()).as(m.getName()).contains("author");
        }
    }

    @Test
    @DisplayName("[QUIZ-CQR-134] 새 풀이자 쿼리는 같은 계정이 그 방에 더 일찍 답한 적이 있는지(not exists)를 본다 - 지난주에 시작한 풀이자의 이번 주 답을 거르는 장치")
    void newSolverQuery_excludesEarlierSolvers() {
        String hql = method(QuizRoomAnswerRepository.class, "countWeeklyNewSolversByRoom")
                .getAnnotation(Query.class).value().toLowerCase();

        assertThat(hql).contains("not exists").contains("distinct");
    }

    @Test
    @DisplayName("[QUIZ-CQR-130, QUIZ-CQR-138] 주 확정 여부·최신 확정 주·순위 조회는 확정 순서(rankNo 오름차순)와 최신 weekStart 기준 파생 쿼리다")
    void weeklyRepositories_derivedQueryContracts() {
        assertThat(method(QuizRoomWeekRepository.class, "existsByWeekStart").getParameterTypes())
                .containsExactly(java.time.LocalDate.class);
        assertThat(method(QuizRoomWeekRepository.class, "findTopByOrderByWeekStartDesc").getReturnType())
                .isEqualTo(java.util.Optional.class);
        assertThat(method(QuizRoomWeeklyBestRepository.class, "findAllByWeek_IdOrderByRankNoAsc").getParameterTypes())
                .containsExactly(Long.class);
    }
}
