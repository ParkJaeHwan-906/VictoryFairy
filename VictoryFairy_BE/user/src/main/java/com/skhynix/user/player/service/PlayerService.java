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
     * 선수를 {@code name} 오름차순으로 반환한다. {@code teamId} 가 있으면 그 구단 소속만, 없으면 전체다.
     * 정렬은 리포지토리(DB)가 수행하며 여기서 다시 정렬하지 않는다.
     *
     * <p>존재하지 않는 {@code teamId} 는 404 가 아니라 빈 리스트다 — 구단 존재 확인에 조회를 한 번 더
     * 쓰는 대신, 이미 공개된 {@code GET /teams} 가 유효한 id 의 출처라는 전제를 따른다.
     */
    public List<PlayerResponse> getPlayers(Long teamId) {
        List<Player> players = (teamId == null)
                ? playerRepository.findAllByOrderByNameAsc()
                : playerRepository.findAllByTeam_IdOrderByNameAsc(teamId);
        return players.stream()
                .map(PlayerResponse::from)
                .toList();
    }
}
