package com.skhynix.user.player.service;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.repository.PlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.user.player.dto.PlayerResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선수 조회 전용 서비스. 선수 데이터는 py-collector 가 소유하므로 쓰기 경로는 두지 않는다
 * ({@code TeamService} 와 같은 이유로 클래스 레벨 {@code readOnly = true}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;

    /**
     * 선수를 {@code name} 오름차순으로 반환한다. 구단 조건은 {@link #resolveTeamId} 가 정한 <b>적용 구단</b>
     * 하나이며, {@code name} 과 AND 결합한다. 정렬·필터링은 리포지토리(DB) 쿼리 4종이 전담하며 여기서
     * 다시 거르지 않는다. 존재하지 않는 구단 id·미일치 {@code name} 은 빈 리스트다.
     *
     * @param userAccountId 인증된 요청의 principal, 비인증(헤더 없음·무효 토큰·탈퇴 계정)이면 {@code null}
     */
    public List<PlayerResponse> getPlayers(Long userAccountId, Long teamId, String name) {
        List<Player> players = findPlayers(resolveTeamId(userAccountId, teamId), normalizeKeyword(name));
        return players.stream()
                .map(PlayerResponse::from)
                .toList();
    }

    /**
     * 적용 구단 결정({@code docs/requirements/user/player-lookup-team-fallback.md}) —
     * ①활성 응원 구단 → ②요청의 {@code teamId} → ③없음(전 구단).
     *
     * <p>활성 응원 구단이 있으면 요청의 {@code teamId} 는 <b>값이 무엇이든</b> 조용히 무시된다(없는 구단
     * id 여도 400·403 이 아니라 응원 구단 결과가 나간다).
     *
     * <p>인증 요청은 {@code teamId} 유무와 무관하게 응원 구단을 1회 조회한다 — 덮어쓸지 판단하려면
     * 봐야 하기 때문이다. 비인증이면 0회다.
     *
     * <p>구단명은 응답에 실리지 않으므로 {@code @EntityGraph} 가 붙은
     * {@code findWithTeamByUserAccount_IdAndOpposeIsNull} 을 쓰지 않는다(불필요한 조인).
     * 프록시의 id 접근은 초기화를 유발하지 않아 구단 조회가 추가로 나가지도 않는다.
     */
    private Long resolveTeamId(Long userAccountId, Long requestedTeamId) {
        if (userAccountId == null) {
            return requestedTeamId;
        }
        return userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(support -> support.getTeam().getId())
                .orElse(requestedTeamId);
    }

    /**
     * 빈 문자열·공백은 {@code null}(검색어 없음)로 정규화한다. {@code ?name=} 처럼 값 없는 쿼리
     * 파라미터는 Spring 이 {@code null} 이 아니라 빈 문자열로 넘기므로, 그대로 흘리면 전체 조회와
     * 결과는 같으면서 인덱스를 못 타는 쿼리가 하나 더 돈다.
     */
    private String normalizeKeyword(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim();
    }

    // 적용 구단이 무엇으로 결정됐든 이후 조회 경로는 기존 4종 조합을 그대로 재사용한다.
    private List<Player> findPlayers(Long appliedTeamId, String keyword) {
        if (appliedTeamId == null) {
            return (keyword == null)
                    ? playerRepository.findAllByOrderByNameAsc()
                    : playerRepository.findAllByNameContainingOrderByNameAsc(keyword);
        }
        return (keyword == null)
                ? playerRepository.findAllByTeam_IdOrderByNameAsc(appliedTeamId)
                : playerRepository.findAllByTeam_IdAndNameContainingOrderByNameAsc(appliedTeamId, keyword);
    }
}
