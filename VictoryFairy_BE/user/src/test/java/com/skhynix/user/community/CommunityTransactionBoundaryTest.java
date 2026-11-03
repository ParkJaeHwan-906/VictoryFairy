package com.skhynix.user.community;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.user.community.service.CommunityCommentCommandService;
import com.skhynix.user.community.service.CommunityCommentWriter;
import com.skhynix.user.community.service.CommunityImageAttacher;
import com.skhynix.user.community.service.CommunityImageEraser;
import com.skhynix.user.community.service.CommunityPostCommandService;
import com.skhynix.user.community.service.CommunityPostQueryService;
import com.skhynix.user.community.service.CommunityPostWriter;
import com.skhynix.user.community.service.CommunityReactionService;
import com.skhynix.user.community.service.CommunityReportService;
import com.skhynix.user.community.store.PostViewWindowStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트랜잭션 경계는 코드에 보이지 않는 계약이라 애노테이션 자체를 고정한다. 단위 테스트(목)로는 트랜잭션이 걸렸는지
 * 알 수 없고, DB 가 없어 통합 검증도 못 하므로 선언이 유일한 증거다.
 * 요구사항: {@code docs/requirements/user/community.md} USER-CM-132, 154, 155, 77, 79.
 */
class CommunityTransactionBoundaryTest {

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + "." + name + " 없음"));
    }

    @Test
    @DisplayName("[AC-CM-132-1] 반응 서비스는 클래스 전체가 쓰기 트랜잭션이다 - 행 변경과 카운트 변경이 한 트랜잭션(readOnly 아님)")
    void reactionService_isWriteTransactional() {
        Transactional tx = CommunityReactionService.class.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }

    @Test
    @DisplayName("신고 서비스도 클래스 전체가 쓰기 트랜잭션이다 - 블라인드, commentCount 감소, 신고 행이 함께 커밋/롤백된다")
    void reportService_isWriteTransactional() {
        Transactional tx = CommunityReportService.class.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }

    @Test
    @DisplayName("[AC-CM-70-1] 게시글 조회 서비스는 클래스가 readOnly지만 상세(getPost)만 쓰기 트랜잭션으로 덮어쓴다 - 조회수 증가가 readOnly에서 조용히 무시되지 않는다")
    void postQueryService_getPostOverridesReadOnly() {
        assertThat(CommunityPostQueryService.class.getAnnotation(Transactional.class).readOnly()).isTrue();

        Transactional detail = method(CommunityPostQueryService.class, "getPost").getAnnotation(Transactional.class);

        assertThat(detail).isNotNull();
        assertThat(detail.readOnly()).isFalse();
        for (String listing : List.of("getPosts", "getPopularPosts", "getMyPosts")) {
            assertThat(method(CommunityPostQueryService.class, listing).getAnnotation(Transactional.class))
                    .as(listing).isNull();
        }
    }

    @Test
    @DisplayName("[AC-CM-154-1, AC-CM-155-1] 지휘자(command 서비스)와 이미지 attacher/eraser에는 @Transactional이 없다 - S3 호출이 DB 트랜잭션 안에 들어가지 않는다")
    void orchestratorsAndS3Collaborators_haveNoTransaction() {
        for (Class<?> type : List.of(CommunityPostCommandService.class, CommunityCommentCommandService.class,
                CommunityImageAttacher.class, CommunityImageEraser.class)) {
            assertThat(type.getAnnotation(Transactional.class)).as(type.getSimpleName()).isNull();
            for (Method m : type.getDeclaredMethods()) {
                assertThat(m.getAnnotation(Transactional.class)).as(type.getSimpleName() + "." + m.getName())
                        .isNull();
            }
        }
    }

    @Test
    @DisplayName("Writer의 쓰기 메서드(create/update/delete)는 쓰기 트랜잭션, prepare*는 readOnly 트랜잭션이다")
    void writers_writeMethodsTransactional_prepareReadOnly() {
        for (Class<?> writer : List.of(CommunityPostWriter.class, CommunityCommentWriter.class)) {
            for (String write : List.of("create", "update", "delete")) {
                Transactional tx = method(writer, write).getAnnotation(Transactional.class);
                assertThat(tx).as(writer.getSimpleName() + "." + write).isNotNull();
                assertThat(tx.readOnly()).as(writer.getSimpleName() + "." + write).isFalse();
            }
            for (Method m : writer.getDeclaredMethods()) {
                if (m.getName().startsWith("prepare")) {
                    Transactional tx = m.getAnnotation(Transactional.class);
                    assertThat(tx).as(m.getName()).isNotNull();
                    assertThat(tx.readOnly()).as(m.getName()).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("[AC-CM-95-1] 게시글 Writer는 댓글 리포지토리에 의존하지 않는다 - 글 삭제가 댓글 행의 deletedAt을 건드릴 수단이 없다")
    void postWriter_hasNoCommentRepositoryDependency() {
        Constructor<?> constructor = CommunityPostWriter.class.getDeclaredConstructors()[0];

        assertThat(Arrays.stream(constructor.getParameterTypes()).map(Class::getSimpleName))
                .noneMatch(n -> n.equals("CommunityCommentRepository"));
    }

    @Test
    @DisplayName("[AC-CM-79-1, AC-CM-195-1] 조회 창 저장소 포트에는 창을 여는 tryOpen 하나뿐이다 - TTL 만료 외에 키를 지우거나 연장하는 경로가 없다")
    void viewWindowStore_hasOnlyTryOpen() {
        assertThat(Arrays.stream(PostViewWindowStore.class.getDeclaredMethods()).map(Method::getName))
                .containsExactly("tryOpen");
    }
}
