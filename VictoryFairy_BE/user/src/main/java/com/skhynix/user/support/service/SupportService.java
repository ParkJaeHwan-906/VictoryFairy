package com.skhynix.user.support.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.repository.PlayerRepository;
import com.skhynix.domain.support.entity.UserSupportPlayer;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportPlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.team.repository.TeamRepository;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.team.dto.TeamResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응원 구단·선수 선택 서비스. 요구사항: {@code docs/requirements/user/support-selection.md}(USER-SP-1 ~ 29).
 *
 * <p><b>이 클래스가 존재하는 이유</b>: {@code .claude/modules/domain.md} 가 응원 도메인에 대해 남긴 경고가
 * "구단 1개 정책은 스키마 제약이 아니라 서비스 정책이고, 이를 강제할 소비처가 없어 아무도 강제하지 않는
 * 정책이다" 였다. 이 서비스가 그 강제 주체다. 지켜야 할 불변식은 네 개다.
 *
 * <ol>
 *   <li>{@code oppose is null} 인 구단 행은 계정당 정확히 1개(USER-SP-12)</li>
 *   <li>응원 선수는 응원 구단 소속(USER-SP-17)</li>
 *   <li>구단이 실제로 바뀌면 응원 선수 전원 취소 — 2를 항상 참으로 유지하기 위한 귀결(USER-SP-10)</li>
 *   <li>취소는 삭제가 아니라 {@code oppose} 컬럼 전이. 행과 최초 취소 시각이 보존된다</li>
 * </ol>
 *
 * <p><b>재응원을 새 행으로 만들면 안 된다</b>: {@code (user_account_id, team_id)}/{@code (…, player_id)} 에
 * UNIQUE 가 걸려 있어 500 이 난다. 그래서 활성 여부를 보는 조회({@code …AndOpposeIsNull}) 와 별개로
 * {@code oppose} 무관 조회({@code findByUserAccount_IdAndTeam_Id} 등)로 취소된 행까지 찾아 재활성한다.
 *
 * <p><b>검증과 변경을 분리한다</b>: 한 요청의 검증을 모두 통과한 뒤에만 상태를 바꾼다. 트랜잭션 롤백에
 * 의존해도 결과는 같지만(USER-SP-3), 검증을 앞에 모아두면 "부분 반영이 없다"가 코드에서 바로 읽힌다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SupportService {

    private final UserSupportTeamRepository userSupportTeamRepository;
    private final UserSupportPlayerRepository userSupportPlayerRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final UserAccountRepository userAccountRepository;

    /**
     * 응원 구단을 선택하거나 변경한다. 최초 선택·변경·재선택을 한 경로가 모두 처리한다 — 클라이언트가
     * 요청 전에 자기 상태를 알아야 하는 왕복을 만들지 않기 위한 결정이다(요구사항 "결정 기록" 참조).
     *
     * <p>같은 구단 재선택은 상태를 건드리지 않는다(USER-SP-7). 이때 응원 선수를 취소하지 않는 것이
     * USER-SP-11 이며, 이 조기 반환이 그 계약을 만든다 — 아래 취소 로직보다 반드시 앞에 있어야 한다.
     *
     * @return 변경 후 현재 응원 구단
     */
    public TeamResponse selectTeam(Long userAccountId, Long teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TEAM_NOT_FOUND));

        Optional<UserSupportTeam> current =
                userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId);
        if (current.isPresent() && current.get().getTeam().getId().equals(teamId)) {
            return TeamResponse.from(team);
        }

        // 한 요청의 모든 취소가 같은 시각을 갖도록 now()를 한 번만 읽는다. 엔티티가 시각을 직접 읽지 않고
        // 호출자에게서 받도록 설계된 이유이기도 하다(USER-SP-10 의 인수 기준이 이 동일성을 검사한다).
        LocalDateTime now = LocalDateTime.now();
        current.ifPresent(previous -> {
            previous.oppose(now);
            opposeAllSupportedPlayers(userAccountId, now);
        });

        UserSupportTeam target = userSupportTeamRepository
                .findByUserAccount_IdAndTeam_Id(userAccountId, teamId)
                .orElseGet(() -> userSupportTeamRepository.save(UserSupportTeam.builder()
                        .userAccount(userAccountRepository.getReferenceById(userAccountId))
                        .team(team)
                        .build()));
        // 신규 행은 이미 oppose=null 이라 무해하고, 과거에 취소했던 행이면 여기서 재활성된다(USER-SP-9).
        target.support();

        return TeamResponse.from(team);
    }

    /**
     * 응원 선수를 추가한다. 전체 교체가 아니라 기존 응원에 얹는다(USER-SP-14) — 그래서 이 경로는 어떤
     * 선수도 취소하지 않으며, 취소는 {@link #opposePlayers} 가 담당한다.
     *
     * @return 추가 후 현재 응원 중인 선수 전체(이번에 추가한 선수만이 아니다 — USER-SP-23)
     */
    public List<PlayerResponse> addPlayers(Long userAccountId, List<Long> playerIds) {
        // 소속 검사의 기준이 되는 구단이 없으면 선수 검증 자체가 불가능하므로 가장 먼저 판정한다(USER-SP-15).
        UserSupportTeam supportTeam = userSupportTeamRepository
                .findByUserAccount_IdAndOpposeIsNull(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPORT_TEAM_REQUIRED));

        List<Long> targetIds = playerIds.stream().distinct().toList();
        if (targetIds.isEmpty()) {
            return currentSupportedPlayers(userAccountId);
        }

        Long supportTeamId = supportTeam.getTeam().getId();
        for (Player player : findAllExisting(targetIds)) {
            // 프록시의 id 접근은 초기화를 유발하지 않으므로 선수마다 팀을 조회하지 않는다.
            if (!player.getTeam().getId().equals(supportTeamId)) {
                throw new BusinessException(ErrorCode.PLAYER_NOT_IN_SUPPORT_TEAM);
            }
        }

        for (Long playerId : targetIds) {
            userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(userAccountId, playerId)
                    .ifPresentOrElse(
                            // 이미 응원 중이면 no-op(USER-SP-18), 취소됐던 행이면 재활성(USER-SP-19).
                            UserSupportPlayer::support,
                            () -> userSupportPlayerRepository.save(UserSupportPlayer.builder()
                                    .userAccount(userAccountRepository.getReferenceById(userAccountId))
                                    .player(playerRepository.getReferenceById(playerId))
                                    .build()));
        }

        return currentSupportedPlayers(userAccountId);
    }

    /**
     * 응원 선수를 취소한다. 행을 지우지 않고 {@code oppose} 에 시각을 채우는 상태 전이이며, 이미 취소된
     * 선수에는 {@code oppose()} 가 no-op 이라 최초 취소 시각이 보존된다(USER-SP-24/25). 이 멱등성이
     * {@code DELETE} 가 아니라 {@code PUT} 을 쓰는 근거다.
     *
     * <p>응원한 적 없는 선수 id 는 404 가 아니다(USER-SP-27) — 요청의 목표 상태("응원하지 않음")가 이미
     * 참이므로 성공으로 처리한다. 반면 <b>존재하지 않는</b> 선수 id 는 404 다(USER-SP-26). 같은 요청에서
     * 두 경우가 다르게 취급되는 것은 의도된 구분이다.
     *
     * @return 취소 후 남아 있는 응원 선수 전체
     */
    public List<PlayerResponse> opposePlayers(Long userAccountId, List<Long> playerIds) {
        List<Long> targetIds = playerIds.stream().distinct().toList();
        if (targetIds.isEmpty()) {
            return currentSupportedPlayers(userAccountId);
        }

        findAllExisting(targetIds); // 존재 검증만 수행한다(USER-SP-26)

        LocalDateTime now = LocalDateTime.now();
        for (Long playerId : targetIds) {
            userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(userAccountId, playerId)
                    .ifPresent(support -> support.oppose(now));
        }

        return currentSupportedPlayers(userAccountId);
    }

    /**
     * 구단이 바뀔 때 현재 응원 중인 선수를 전원 취소한다. 선수는 응원 구단 소속이어야 하므로(USER-SP-17)
     * 구단이 바뀌면 기존 응원이 그 불변식을 깨뜨린다 — 그래서 조용히 정리하는 것이 계약이다.
     * 프론트는 구단 변경 전에 "선수 선택도 초기화됩니다"를 고지해야 한다(서버는 경고하지 않는다).
     */
    private void opposeAllSupportedPlayers(Long userAccountId, LocalDateTime now) {
        userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(userAccountId)
                .forEach(support -> support.oppose(now));
    }

    /**
     * 주어진 id 전부가 실재하는 선수인지 확인하고 그 엔티티를 돌려준다. 하나라도 없으면 404 이며, 같은
     * 요청의 나머지도 반영되지 않는다(USER-SP-16/26 + USER-SP-3).
     *
     * <p>{@code findAllById} 는 없는 id 를 조용히 빼고 돌려주므로 <b>개수 비교로만</b> 누락을 감지할 수
     * 있다. 호출 전에 중복이 제거돼 있어야 이 비교가 성립한다.
     */
    private List<Player> findAllExisting(List<Long> distinctPlayerIds) {
        List<Player> players = playerRepository.findAllById(distinctPlayerIds);
        if (players.size() != distinctPlayerIds.size()) {
            throw new BusinessException(ErrorCode.PLAYER_NOT_FOUND);
        }
        return players;
    }

    /**
     * 현재 응원 중인 선수를 name 오름차순으로 반환한다.
     *
     * <p>응원 행에서 FK 값만 모아 선수를 한 번에 조회한다 — {@code UserSupportPlayer.player} 가 LAZY 라
     * 행마다 {@code getPlayer().getName()} 을 부르면 선수 수만큼 조회가 나가기 때문이다(프록시의 id
     * 접근은 초기화를 유발하지 않아 첫 루프는 쿼리를 만들지 않는다).
     */
    @Transactional(readOnly = true)
    public List<PlayerResponse> currentSupportedPlayers(Long userAccountId) {
        List<Long> playerIds = userSupportPlayerRepository
                .findAllByUserAccount_IdAndOpposeIsNull(userAccountId)
                .stream()
                .map(support -> support.getPlayer().getId())
                .toList();
        if (playerIds.isEmpty()) {
            return List.of();
        }
        return playerRepository.findAllByIdInOrderByNameAsc(playerIds)
                .stream()
                .map(PlayerResponse::from)
                .toList();
    }
}
