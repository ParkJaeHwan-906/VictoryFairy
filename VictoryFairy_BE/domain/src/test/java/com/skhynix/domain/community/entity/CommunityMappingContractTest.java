package com.skhynix.domain.community.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 커뮤니티 엔티티 선언이 요구사항의 <b>스키마 계약</b>(FK 삭제 정책, UNIQUE, 서수 고정, uid 부재)을 그대로 담는지
 * 리플렉션으로 고정한다. DB 가 없어 DDL 을 실제로 만들어 볼 수는 없으므로(H2/Testcontainers 부재) 선언 자체가 유일한
 * 증거다. 요구사항: {@code docs/requirements/user/community.md} USER-CM-5, 10, 125, 175, 192~194, 201.
 */
class CommunityMappingContractTest {

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

    private static Table table(Class<?> type) {
        return type.getAnnotation(Table.class);
    }

    // ---------- FK 삭제 정책 ----------

    @Test
    @DisplayName("[AC-CM-194-1] 게시글·댓글의 작성자 FK에는 @OnDelete가 없다 - 이관 없이 계정을 지우면 FK 위반으로 시끄럽게 실패한다(fail-closed)")
    void authorForeignKeys_haveNoOnDelete() {
        assertThat(onDelete(CommunityPost.class, "userAccount")).isNull();
        assertThat(onDelete(CommunityComment.class, "userAccount")).isNull();
        assertThat(field(CommunityPost.class, "userAccount").getAnnotation(JoinColumn.class).nullable()).isFalse();
        assertThat(field(CommunityComment.class, "userAccount").getAnnotation(JoinColumn.class).nullable())
                .isFalse();
    }

    @Test
    @DisplayName("[AC-CM-192-1, AC-CM-193-1] 반응 2종의 계정 FK와 신고자 FK는 SET_NULL + nullable이다(집계·신고 근거 보존)")
    void reactionAndReportAccountForeignKeys_areSetNullAndNullable() {
        assertThat(onDelete(CommunityPostReaction.class, "userAccount")).isEqualTo(OnDeleteAction.SET_NULL);
        assertThat(onDelete(CommunityCommentReaction.class, "userAccount")).isEqualTo(OnDeleteAction.SET_NULL);
        assertThat(onDelete(CommunityReport.class, "reporter")).isEqualTo(OnDeleteAction.SET_NULL);
        for (Field f : Arrays.asList(field(CommunityPostReaction.class, "userAccount"),
                field(CommunityCommentReaction.class, "userAccount"), field(CommunityReport.class, "reporter"))) {
            assertThat(f.getAnnotation(JoinColumn.class).nullable()).as(f.getName()).isTrue();
            assertThat(f.getAnnotation(ManyToOne.class).optional()).as(f.getName()).isTrue();
        }
    }

    @Test
    @DisplayName("[AC-CM-108-1, AC-CM-210-1] 댓글 테이블의 parent는 nullable 자기 참조이고 DB 단 CASCADE다(앱 삭제는 deletedAt만 채우며 전파하지 않는다)")
    void commentParent_isNullableSelfReferenceWithDbCascade() {
        Field parent = field(CommunityComment.class, "parent");

        assertThat(parent.getType()).isEqualTo(CommunityComment.class);
        assertThat(parent.getAnnotation(ManyToOne.class).optional()).isTrue();
        assertThat(parent.getAnnotation(JoinColumn.class).name()).isEqualTo("parent_comment_id");
        assertThat(onDelete(CommunityComment.class, "parent")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(onDelete(CommunityComment.class, "post")).isEqualTo(OnDeleteAction.CASCADE);
    }

    @Test
    @DisplayName("[USER-CM-147 데이터 모델] 반응·이미지의 부모(글/댓글) FK는 CASCADE다")
    void childRowsCascadeFromParents() {
        assertThat(onDelete(CommunityPostReaction.class, "post")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(onDelete(CommunityCommentReaction.class, "comment")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(onDelete(CommunityPostImage.class, "post")).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(onDelete(CommunityCommentImage.class, "comment")).isEqualTo(OnDeleteAction.CASCADE);
    }

    @Test
    @DisplayName("[AC-CM-10-1] 카테고리의 team FK는 nullable(자유게시판)이고 @OnDelete가 없다(마스터 데이터), 글의 category FK도 @OnDelete 없음")
    void categoryForeignKeys_areMasterDataStyle() {
        assertThat(field(CommunityCategory.class, "team").getAnnotation(ManyToOne.class).optional()).isTrue();
        assertThat(onDelete(CommunityCategory.class, "team")).isNull();
        assertThat(onDelete(CommunityPost.class, "category")).isNull();
    }

    // ---------- UNIQUE / 인덱스 ----------

    private static UniqueConstraint uniqueOf(Class<?> type, String name) {
        return Arrays.stream(table(type).uniqueConstraints()).filter(u -> u.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " 에 UNIQUE " + name + " 가 없다"));
    }

    @Test
    @DisplayName("[AC-CM-125-1] 반응 UNIQUE는 이름이 명시된 (user_account_id, post_id) / (user_account_id, comment_id)다")
    void reactionUniqueConstraints_areNamedAndCoverAccountAndTarget() {
        assertThat(uniqueOf(CommunityPostReaction.class, "uk_community_post_reactions_account_post").columnNames())
                .containsExactly("user_account_id", "post_id");
        assertThat(uniqueOf(CommunityCommentReaction.class, "uk_community_comment_reactions_account_comment")
                .columnNames()).containsExactly("user_account_id", "comment_id");
    }

    @Test
    @DisplayName("[AC-CM-175-2] 신고 UNIQUE는 (reporter_account_id, target_type, target_id)다")
    void reportUniqueConstraint_coversReporterAndTarget() {
        assertThat(uniqueOf(CommunityReport.class, "uk_community_reports_reporter_target").columnNames())
                .containsExactly("reporter_account_id", "target_type", "target_id");
    }

    @Test
    @DisplayName("카테고리 name UNIQUE(시드 anti-join 보호)와 글 created_at 인덱스(인기 7일 범위 스캔)가 선언돼 있다")
    void categoryUniqueNameAndPostCreatedAtIndex() {
        assertThat(uniqueOf(CommunityCategory.class, "uk_community_categories_name").columnNames())
                .containsExactly("name");
        assertThat(table(CommunityPost.class).indexes()).anyMatch(
                i -> i.name().equals("idx_community_posts_created_at") && i.columnList().equals("created_at"));
    }

    // ---------- 서수 / 식별자 / 기본값 ----------

    @Test
    @DisplayName("[AC-CM-125-1 ORDINAL] ReactionType은 LIKE=0, DISLIKE=1, ReportTargetType은 POST=0, COMMENT=1 - 선언 순서가 저장값이라 바뀌면 안 된다")
    void enumOrdinals_areFrozen() {
        assertThat(ReactionType.LIKE.ordinal()).isZero();
        assertThat(ReactionType.DISLIKE.ordinal()).isEqualTo(1);
        assertThat(ReactionType.values()).hasSize(2);
        assertThat(ReportTargetType.POST.ordinal()).isZero();
        assertThat(ReportTargetType.COMMENT.ordinal()).isEqualTo(1);
        assertThat(ReportTargetType.values()).hasSize(2);
    }

    @Test
    @DisplayName("[AC-CM-5-3] 게시글·댓글 테이블에는 uid 컬럼이 없다(외부 식별자는 내부 PK)")
    void postAndComment_haveNoUidColumn() {
        for (Class<?> type : Arrays.asList(CommunityPost.class, CommunityComment.class)) {
            assertThat(Arrays.stream(type.getDeclaredFields()).map(Field::getName)).as(type.getSimpleName())
                    .doesNotContain("uid");
        }
    }

    @Test
    @DisplayName("[USER-CM-21 데이터 모델] 카운터 컬럼은 @ColumnDefault(\"0\")이고 blinded는 TINYINT이다")
    void counters_haveZeroDefaultAndBlindedIsTinyint() {
        for (String counter : Arrays.asList("viewCount", "likeCount", "dislikeCount", "commentCount")) {
            assertThat(field(CommunityPost.class, counter).getAnnotation(ColumnDefault.class).value())
                    .as(counter).isEqualTo("0");
        }
        for (String counter : Arrays.asList("likeCount", "dislikeCount")) {
            assertThat(field(CommunityComment.class, counter).getAnnotation(ColumnDefault.class).value())
                    .as(counter).isEqualTo("0");
        }
        assertThat(field(CommunityPost.class, "blinded").getAnnotation(Column.class).columnDefinition())
                .isEqualTo("TINYINT");
        assertThat(field(CommunityComment.class, "blinded").getAnnotation(Column.class).columnDefinition())
                .isEqualTo("TINYINT");
    }

    @Test
    @DisplayName("테이블명은 복수형 스네이크다(community_*)")
    void tableNames() {
        assertThat(table(CommunityCategory.class).name()).isEqualTo("community_categories");
        assertThat(table(CommunityPost.class).name()).isEqualTo("community_posts");
        assertThat(table(CommunityComment.class).name()).isEqualTo("community_comments");
        assertThat(table(CommunityPostImage.class).name()).isEqualTo("community_post_images");
        assertThat(table(CommunityCommentImage.class).name()).isEqualTo("community_comment_images");
        assertThat(table(CommunityPostReaction.class).name()).isEqualTo("community_post_reactions");
        assertThat(table(CommunityCommentReaction.class).name()).isEqualTo("community_comment_reactions");
        assertThat(table(CommunityReport.class).name()).isEqualTo("community_reports");
    }
}
