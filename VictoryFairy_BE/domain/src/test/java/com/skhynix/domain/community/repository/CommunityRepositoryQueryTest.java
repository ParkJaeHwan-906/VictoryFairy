package com.skhynix.domain.community.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.community.entity.CommunityCategory;
import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityCommentImage;
import com.skhynix.domain.community.entity.CommunityCommentReaction;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.CommunityPostImage;
import com.skhynix.domain.community.entity.CommunityPostReaction;
import com.skhynix.domain.community.entity.CommunityReport;
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
import org.springframework.data.jpa.repository.Query;

/**
 * 커뮤니티 리포지토리 {@code @Query} 의 <b>HQL 구문·매핑 검증</b> - DB 없이 Hibernate 메타모델만 올려
 * {@code createQuery} 로 파싱·의미 분석을 통과시킨다(연결은 열지 않는다).
 *
 * <p>⚠ 이것은 {@code @DataJpaTest} 를 대신하지 않는다. 쿼리가 <b>돌려주는 결과</b>(EXISTS 로 자리 표식이 실제로 걸러지는지,
 * 정렬 결과, {@code Limit}, 0 하한, UNIQUE 위반 등)는 실제 DB 가 있어야 검증할 수 있고, 이 저장소는 H2/Testcontainers 가
 * 없다(결정 필요). 여기서는 "프로퍼티 이름이 틀려 앱이 기동조차 못 하는" 종류의 사고와 본문·count WHERE 불일치만 막는다.
 */
class CommunityRepositoryQueryTest {

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
                .addAnnotatedClass(CommunityCategory.class)
                .addAnnotatedClass(CommunityPost.class)
                .addAnnotatedClass(CommunityComment.class)
                .addAnnotatedClass(CommunityPostImage.class)
                .addAnnotatedClass(CommunityCommentImage.class)
                .addAnnotatedClass(CommunityPostReaction.class)
                .addAnnotatedClass(CommunityCommentReaction.class)
                .addAnnotatedClass(CommunityReport.class);
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
        return List.of(CommunityPostRepository.class, CommunityCommentRepository.class,
                CommunityPostImageRepository.class, CommunityCommentImageRepository.class,
                CommunityPostReactionRepository.class, CommunityCommentReactionRepository.class,
                CommunityReportRepository.class, CommunityCategoryRepository.class);
    }

    private static Query queryOf(Class<?> repository, String method) {
        for (Method m : repository.getDeclaredMethods()) {
            if (m.getName().equals(method) && m.getAnnotation(Query.class) != null) {
                return m.getAnnotation(Query.class);
            }
        }
        throw new AssertionError(repository.getSimpleName() + "." + method + " 에 @Query 가 없다");
    }

    @Test
    @DisplayName("커뮤니티 리포지토리의 모든 @Query(및 countQuery)가 엔티티 메타모델로 파싱·의미 분석을 통과한다 - 프로퍼티명 오타 시 기동 불가 사고 방지")
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
                "CommunityPostRepository.findPopular",
                "CommunityPostRepository.findPopularByCategory",
                "CommunityPostRepository.increaseViewCount",
                "CommunityPostRepository.adjustCommentCount",
                "CommunityPostRepository.reassignAuthor",
                "CommunityPostRepository.findWithLockByIdAndDeletedAtIsNull",
                "CommunityCommentRepository.findTopLevelPage",
                "CommunityCommentRepository.findTopLevelPage#count",
                "CommunityCommentRepository.findVisibleRepliesByParentIds",
                "CommunityCommentRepository.reassignAuthor",
                "CommunityCommentRepository.findWithLockByIdAndDeletedAtIsNull",
                "CommunityCommentImageRepository.findEndpointsByPostId");
    }

    @Test
    @DisplayName("[AC-CM-105-2, AC-CM-212-3] findTopLevelPage: 본문 쿼리와 countQuery의 WHERE 절이 글자 그대로 같다 - total과 페이지 내용이 어긋나지 않는다")
    void findTopLevelPage_bodyAndCountQueryShareWhereClause() {
        Query query = queryOf(CommunityCommentRepository.class, "findTopLevelPage");

        String body = query.value();
        String count = query.countQuery();
        String bodyWhere = body.substring(body.indexOf(" where "), body.indexOf(" order by "));
        String countWhere = count.substring(count.indexOf(" where "));

        assertThat(bodyWhere).isEqualTo(countWhere);
        assertThat(body).contains("c.parent is null").contains("exists (");
        assertThat(body).endsWith("order by c.id asc");
    }

    @Test
    @DisplayName("[AC-CM-51-1, AC-CM-51-2, AC-CM-52-1] 인기 쿼리(전체/카테고리)의 정렬식은 (likeCount*10 + viewCount) 내림차순 -> id 내림차순이고 싫어요는 식에 없다")
    void popularQueries_orderByScoreThenIdDesc_withoutDislike() {
        for (String method : List.of("findPopular", "findPopularByCategory")) {
            String hql = queryOf(CommunityPostRepository.class, method).value();

            assertThat(hql).as(method).endsWith("order by (p.likeCount * 10 + p.viewCount) desc, p.id desc");
            assertThat(hql).as(method).doesNotContain("dislikeCount");
            assertThat(hql).as(method)
                    .contains("p.deletedAt is null").contains("p.blinded = false")
                    .contains("p.createdAt >= :since");
        }
        assertThat(queryOf(CommunityPostRepository.class, "findPopularByCategory").value())
                .contains("p.category.id = :categoryId");
    }

    @Test
    @DisplayName("[AC-CM-77-1] 조회수 증가는 read-modify-write가 아니라 원자 UPDATE 한 문장이다")
    void increaseViewCount_isAtomicUpdate() {
        assertThat(queryOf(CommunityPostRepository.class, "increaseViewCount").value())
                .isEqualTo("update CommunityPost p set p.viewCount = p.viewCount + 1 where p.id = :id");
    }

    @Test
    @DisplayName("[AC-CM-191-3] 작성자 이관 UPDATE는 삭제·블라인드 여부로 거르지 않는다(deletedAt/blinded 조건이 없다) - 게시글·댓글 모두")
    void reassignAuthor_doesNotFilterDeletedOrBlinded() {
        for (Class<?> repository : List.of(CommunityPostRepository.class, CommunityCommentRepository.class)) {
            String hql = queryOf(repository, "reassignAuthor").value();

            assertThat(hql).as(repository.getSimpleName()).startsWith("update ")
                    .doesNotContain("deletedAt").doesNotContain("blinded").doesNotContain("parent");
        }
    }

    @Test
    @DisplayName("[AC-CM-131-1] 반응 변경용 락 쿼리는 PESSIMISTIC_WRITE이고 삭제된 행을 제외한다 - 게시글·댓글 모두")
    void lockQueries_arePessimisticWriteAndSkipDeleted() throws NoSuchMethodException {
        Method postLock = CommunityPostRepository.class.getMethod("findWithLockByIdAndDeletedAtIsNull", Long.class);
        Method commentLock = CommunityCommentRepository.class.getMethod("findWithLockByIdAndDeletedAtIsNull",
                Long.class);

        for (Method lock : List.of(postLock, commentLock)) {
            org.springframework.data.jpa.repository.Lock annotation =
                    lock.getAnnotation(org.springframework.data.jpa.repository.Lock.class);
            assertThat(annotation).as(lock.getDeclaringClass().getSimpleName()).isNotNull();
            assertThat(annotation.value()).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            assertThat(lock.getAnnotation(Query.class).value()).contains("deletedAt is null");
        }
    }

    @Test
    @DisplayName("[AC-CM-113-1] 댓글 수 조정은 0 아래로 내려가지 않게 case 식으로 하한을 둔다")
    void adjustCommentCount_hasZeroFloorInQuery() {
        assertThat(queryOf(CommunityPostRepository.class, "adjustCommentCount").value())
                .contains("case when p.commentCount + :delta < 0 then 0 else p.commentCount + :delta end");
    }
}
