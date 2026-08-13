package com.skhynix.user.player.service;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.repository.PlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.user.player.dto.PlayerResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;

    public List<PlayerResponse> getPlayers(Long userAccountId, Long teamId, String name) {
        List<Player> players = findPlayers(resolveTeamId(userAccountId, teamId), normalizeKeyword(name));
        return players.stream()
                .map(PlayerResponse::from)
                .toList();
    }

    // 구단명이 응답에 안 실려 @EntityGraph 가 붙은 findWithTeamBy... 를 쓰지 않는다(불필요한 조인).
    // 프록시의 id 접근은 초기화를 유발하지 않아 구단 조회가 추가로 나가지도 않는다.
    private Long resolveTeamId(Long userAccountId, Long requestedTeamId) {
        if (userAccountId == null) {
            return requestedTeamId;
        }
        return userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(support -> support.getTeam().getId())
                .orElse(requestedTeamId);
    }

    // ?name= 처럼 값 없는 쿼리 파라미터를 Spring 이 null 이 아니라 빈 문자열로 넘기므로 여기서 접는다.
    private String normalizeKeyword(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim();
    }

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
