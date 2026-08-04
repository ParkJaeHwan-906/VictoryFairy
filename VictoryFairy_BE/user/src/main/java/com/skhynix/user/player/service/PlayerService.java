package com.skhynix.user.player.service;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.repository.PlayerRepository;
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

    /**
     * 선수를 {@code name} 오름차순으로 반환한다. {@code teamId}·{@code name} 은 AND 결합, 둘 다 없으면
     * 전체다. 정렬·필터링은 리포지토리(DB) 쿼리 4종이 전담하며 여기서 다시 거르지 않는다. 존재하지 않는
     * {@code teamId}·미일치 {@code name} 은 빈 리스트다.
     */
    public List<PlayerResponse> getPlayers(Long teamId, String name) {
        List<Player> players = findPlayers(teamId, normalizeKeyword(name));
        return players.stream()
                .map(PlayerResponse::from)
                .toList();
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

    private List<Player> findPlayers(Long teamId, String keyword) {
        if (teamId == null) {
            return (keyword == null)
                    ? playerRepository.findAllByOrderByNameAsc()
                    : playerRepository.findAllByNameContainingOrderByNameAsc(keyword);
        }
        return (keyword == null)
                ? playerRepository.findAllByTeam_IdOrderByNameAsc(teamId)
                : playerRepository.findAllByTeam_IdAndNameContainingOrderByNameAsc(teamId, keyword);
    }
}
