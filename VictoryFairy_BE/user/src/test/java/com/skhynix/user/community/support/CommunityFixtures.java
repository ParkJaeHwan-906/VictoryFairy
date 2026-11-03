package com.skhynix.user.community.support;

import com.skhynix.domain.community.entity.CommunityCategory;
import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 커뮤니티 테스트 공용 픽스처 — DB 가 없어 id·타임스탬프를 리플렉션으로 채운 순수 인메모리 엔티티를 만든다.
 * 빌더가 id 와 카운터를 받지 않는 것(컨벤션)을 테스트가 우회하는 자리는 여기 한 곳뿐이다.
 */
public final class CommunityFixtures {

    public static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 12, 0, 0);

    private CommunityFixtures() {
    }

    public static UserAccount account(long id, String nickname) {
        UserAccount account = UserAccount.builder().nickname(nickname).password("password1!").build();
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    public static CommunityCategory category(long id, String name) {
        CommunityCategory category = CommunityCategory.builder().name(name).sortOrder((int) id).build();
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    public static CommunityCategory teamCategory(long id, String name, long teamId) {
        Team team = Team.builder().name(name).build();
        ReflectionTestUtils.setField(team, "id", teamId);
        CommunityCategory category = CommunityCategory.builder().name(name).team(team)
                .sortOrder((int) id).build();
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    public static CommunityPost post(long id, CommunityCategory category, UserAccount author) {
        CommunityPost post = CommunityPost.builder().category(category).userAccount(author)
                .title("제목" + id).content("본문" + id).build();
        ReflectionTestUtils.setField(post, "id", id);
        ReflectionTestUtils.setField(post, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(post, "updatedAt", CREATED_AT);
        return post;
    }

    public static CommunityComment comment(long id, CommunityPost post, CommunityComment parent,
            UserAccount author) {
        CommunityComment comment = CommunityComment.builder().post(post).parent(parent)
                .userAccount(author).content("댓글" + id).build();
        ReflectionTestUtils.setField(comment, "id", id);
        ReflectionTestUtils.setField(comment, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(comment, "updatedAt", CREATED_AT);
        return comment;
    }

    public static void set(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }
}
