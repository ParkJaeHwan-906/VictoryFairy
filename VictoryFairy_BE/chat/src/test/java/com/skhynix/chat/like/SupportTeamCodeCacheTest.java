package com.skhynix.chat.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.like.service.SupportTeamCodeCache;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 응원 구단 코드 메모리 캐시. 리포지토리는 목이다. */
class SupportTeamCodeCacheTest {

    private UserSupportTeamRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(UserSupportTeamRepository.class);
    }

    private SupportTeamCodeCache cache(long ttlSeconds) {
        return new SupportTeamCodeCache(repository, new ChatLikesProperties(
                new ChatLikesProperties.RateLimit(10), ttlSeconds, 100, 10_000));
    }

    private static Optional<UserSupportTeam> support(String code) {
        Team team = mock(Team.class);
        when(team.getCode()).thenReturn(code);
        UserSupportTeam support = mock(UserSupportTeam.class);
        when(support.getTeam()).thenReturn(team);
        return Optional.of(support);
    }

    private void teamIs(long userId, String code) {
        Optional<UserSupportTeam> support = support(code);
        given(repository.findWithTeamByUserAccount_IdAndOpposeIsNull(userId)).willReturn(support);
    }

    @Test
    @DisplayName("[CHAT-LK-10] 응원 행의 teams.code 를 돌려준다")
    void returnsTeamCodeOfSupportRow() {
        teamIs(1L, "HT");

        assertThat(cache(300).teamCode(1L)).contains("HT");
    }

    @Test
    @DisplayName("[CHAT-LK-11] 같은 사용자가 TTL 안에 100회 조회해도 DB 조회는 1회다")
    void hundredLookupsWithinTtl_singleSelect() {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(300);

        for (int i = 0; i < 100; i++) {
            assertThat(cache.teamCode(1L)).contains("HT");
        }

        verify(repository, times(1)).findWithTeamByUserAccount_IdAndOpposeIsNull(1L);
    }

    @Test
    @DisplayName("[CHAT-LK-11] 캐시는 사용자별이다 — 다른 사용자의 첫 조회는 DB 를 친다")
    void cacheIsPerUser() {
        teamIs(1L, "HT");
        teamIs(2L, "LG");
        SupportTeamCodeCache cache = cache(300);

        assertThat(cache.teamCode(1L)).contains("HT");
        assertThat(cache.teamCode(2L)).contains("LG");

        verify(repository, times(1)).findWithTeamByUserAccount_IdAndOpposeIsNull(1L);
        verify(repository, times(1)).findWithTeamByUserAccount_IdAndOpposeIsNull(2L);
    }

    @Test
    @DisplayName("[CHAT-LK-12] 구단을 바꿔도 TTL 이 끝나기 전에는 옛 코드를 돌려주고, TTL(1초) 뒤에는 새 코드를 돌려준다")
    void changeIsInvisibleUntilTtlThenVisible() throws Exception {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(1);
        assertThat(cache.teamCode(1L)).contains("HT");

        teamIs(1L, "LG");
        assertThat(cache.teamCode(1L)).as("TTL 안: 옛 값").contains("HT");

        Thread.sleep(1_150);
        assertThat(cache.teamCode(1L)).as("TTL 뒤: 새 값").contains("LG");
    }

    @Test
    @DisplayName("[CHAT-LK-12] TTL 은 첫 조회부터 센다 — 계속 조회(접근)해도 만료가 미뤄지지 않는다")
    void ttlIsNotExtendedByAccess() throws Exception {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(1);
        assertThat(cache.teamCode(1L)).contains("HT");
        teamIs(1L, "LG");

        long deadline = System.nanoTime() + 2_500_000_000L;
        Optional<String> seen = Optional.of("HT");
        while (seen.equals(Optional.of("HT")) && System.nanoTime() < deadline) {
            Thread.sleep(200);
            seen = cache.teamCode(1L);
        }

        assertThat(seen).as("1초 TTL 이 접근으로 연장되지 않고 새 값이 보인다").contains("LG");
    }

    @Test
    @DisplayName("[CHAT-LK-14] 응원 행이 없으면 empty 이고 캐시하지 않는다 — 구단을 설정한 직후 5분을 기다리지 않고 그 코드가 나온다")
    void emptyResultIsNotCached() {
        given(repository.findWithTeamByUserAccount_IdAndOpposeIsNull(1L)).willReturn(Optional.empty());
        SupportTeamCodeCache cache = cache(300);
        assertThat(cache.teamCode(1L)).isEmpty();
        assertThat(cache.teamCode(1L)).isEmpty();

        teamIs(1L, "HT");

        assertThat(cache.teamCode(1L)).contains("HT");
        verify(repository, times(3)).findWithTeamByUserAccount_IdAndOpposeIsNull(1L);
    }

    @Test
    @DisplayName("[CHAT-LK-14] 구단 코드가 공백 문자열이면 비어 있는 것으로 보고 캐시하지 않는다")
    void blankCodeTreatedAsEmpty() {
        teamIs(1L, "  ");
        SupportTeamCodeCache cache = cache(300);

        assertThat(cache.teamCode(1L)).isEmpty();
        assertThat(cache.teamCode(1L)).isEmpty();
        verify(repository, times(2)).findWithTeamByUserAccount_IdAndOpposeIsNull(1L);
    }

    @Test
    @DisplayName("[CHAT-LK-14] 만료된 항목을 다시 조회했더니 응원 행이 사라졌으면 옛 값을 돌려주지 않고 empty 다")
    void expiredEntryThenRowGone_returnsEmptyNotStale() throws Exception {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(1);
        assertThat(cache.teamCode(1L)).contains("HT");
        given(repository.findWithTeamByUserAccount_IdAndOpposeIsNull(1L)).willReturn(Optional.empty());

        Thread.sleep(1_150);

        assertThat(cache.teamCode(1L)).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-LK-15] DB 조회가 실패하면 예외를 그대로 던지고(호출부가 버린다) 실패는 캐시되지 않아 다음 조회가 다시 DB 를 친다")
    void lookupFailure_propagates_andIsNotCached() {
        Optional<UserSupportTeam> support = support("HT");
        given(repository.findWithTeamByUserAccount_IdAndOpposeIsNull(1L))
                .willThrow(new IllegalStateException("DB down"))
                .willReturn(support);
        SupportTeamCodeCache cache = cache(300);

        assertThatThrownBy(() -> cache.teamCode(1L)).isInstanceOf(IllegalStateException.class).hasMessage("DB down");
        assertThat(cache.teamCode(1L)).contains("HT");
    }

    @Test
    @DisplayName("[CHAT-LK-15] 캐시에 든 사용자는 DB 가 죽어도 영향 없이 코드를 돌려준다")
    void cachedUserUnaffectedByDbFailure() {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(300);
        cache.teamCode(1L);
        given(repository.findWithTeamByUserAccount_IdAndOpposeIsNull(1L)).willThrow(new IllegalStateException("DB down"));

        assertThat(cache.teamCode(1L)).contains("HT");
    }

    @Test
    @DisplayName("[CHAT-LK-11] evictExpired 는 만료된 항목만 걷어낸다 — 살아 있는 항목은 DB 재조회 없이 남는다")
    void evictExpired_keepsLiveEntries() {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(300);
        cache.teamCode(1L);

        cache.evictExpired();
        cache.teamCode(1L);

        verify(repository, times(1)).findWithTeamByUserAccount_IdAndOpposeIsNull(1L);
    }
}
