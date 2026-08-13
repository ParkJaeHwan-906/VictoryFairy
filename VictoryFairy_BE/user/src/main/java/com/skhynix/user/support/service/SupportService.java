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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 재응원을 새 행으로 만들면 안 된다 — (user_account_id, team_id)/(…, player_id) 에 UNIQUE 가 걸려 있어
// 그대로 save 하면 500 이다. 그래서 oppose 무관 조회로 취소된 행까지 찾아 재활성한다.
@Service
@RequiredArgsConstructor
@Transactional
public class SupportService {

    /** 응원 선수 개수 상한의 단일 출처. 이 값을 다른 곳에 다시 적지 말 것. */
    private static final int MAX_SUPPORT_PLAYERS = 4;

    private final UserSupportTeamRepository userSupportTeamRepository;
    private final UserSupportPlayerRepository userSupportPlayerRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final UserAccountRepository userAccountRepository;

    public TeamResponse selectTeam(Long userAccountId, Long teamId) {
        // 구단 변경은 응원 선수를 전원 취소한다. 락이 없으면 그 사이 들어온 addPlayers 가 옛 구단 선수를
        // 얹어 "응원 선수는 응원 구단 소속" 불변식이 깨진다.
        lockAccount(userAccountId);

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TEAM_NOT_FOUND));

        Optional<UserSupportTeam> current =
                userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId);
        if (current.isPresent() && current.get().getTeam().getId().equals(teamId)) {
            return TeamResponse.from(team);
        }

        // 한 요청의 모든 취소가 같은 시각을 갖도록 now()를 한 번만 읽는다(엔티티가 시각을 직접 읽지
        // 않고 호출자에게서 받도록 설계된 이유이기도 하다).
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
        // 신규 행은 이미 oppose=null 이라 무해하고, 과거에 취소했던 행이면 여기서 재활성된다.
        target.support();

        return TeamResponse.from(team);
    }

    public List<PlayerResponse> addPlayers(Long userAccountId, List<Long> playerIds) {
        // 상한 판정(읽기→판정→저장)이 원자적이어야 한다. 락 없이는 활성 2명 계정에 [a,b]·[c,d] 가 동시에
        // 들어오면 두 트랜잭션이 각각 합집합 4로 통과해 최종 6명이 된다.
        lockAccount(userAccountId);

        // 소속 검사 기준이 되는 구단이 없으면 선수 검증 자체가 불가능하므로 가장 먼저 판정한다.
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

        validatePlayerLimit(userAccountId, targetIds);

        for (Long playerId : targetIds) {
            userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(userAccountId, playerId)
                    .ifPresentOrElse(
                            // 이미 응원 중이면 no-op, 취소됐던 행이면 재활성.
                            UserSupportPlayer::support,
                            () -> userSupportPlayerRepository.save(UserSupportPlayer.builder()
                                    .userAccount(userAccountRepository.getReferenceById(userAccountId))
                                    .player(playerRepository.getReferenceById(playerId))
                                    .build()));
        }

        return currentSupportedPlayers(userAccountId);
    }

    // 이미 응원 중인 선수를 다시 보내도 개수가 늘지 않으므로 "현재 + 요청" 합이 아니라 합집합 크기로 센다.
    private void validatePlayerLimit(Long userAccountId, List<Long> targetIds) {
        Set<Long> resultingIds = new HashSet<>(targetIds);
        userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(userAccountId)
                // 프록시의 id 접근은 초기화를 유발하지 않는다(선수 이름 등을 읽으면 N+1).
                .forEach(support -> resultingIds.add(support.getPlayer().getId()));

        if (resultingIds.size() > MAX_SUPPORT_PLAYERS) {
            throw new BusinessException(ErrorCode.SUPPORT_PLAYER_LIMIT_EXCEEDED);
        }
    }

    public List<PlayerResponse> opposePlayers(Long userAccountId, List<Long> playerIds) {
        // 취소는 개수를 줄이는 방향이라 상한과는 무관하지만, 응원 행을 UPDATE 하며 행 락을 잡는다. 다른
        // 쓰기 경로가 계정 락 → 응원 행 락 순서인데 여기만 응원 행부터 잡으면 락 순서가 역전돼 데드락이
        // 난다. 같은 순서로 맞춘다.
        lockAccount(userAccountId);

        List<Long> targetIds = playerIds.stream().distinct().toList();
        if (targetIds.isEmpty()) {
            return currentSupportedPlayers(userAccountId);
        }

        findAllExisting(targetIds); // 존재 검증만 수행한다

        LocalDateTime now = LocalDateTime.now();
        for (Long playerId : targetIds) {
            userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(userAccountId, playerId)
                    .ifPresent(support -> support.oppose(now));
        }

        return currentSupportedPlayers(userAccountId);
    }

    // 쓰기 경로는 예외 없이 이 호출을 가장 먼저 한다 — 순서가 흔들리면 락 순서 역전으로 데드락이 난다.
    // 읽기 전용 경로(currentSupportedPlayers)에는 절대 걸지 않는다 — GET /me 가 그 경로를 탄다.
    private void lockAccount(Long userAccountId) {
        // 필터가 활성 계정임을 확인한 id라 정상 경로에서는 항상 존재한다. 그 사이 사라졌다면 인증 근거가
        // 사라진 것이므로 다른 경로들과 같은 401로 맞춘다.
        userAccountRepository.findWithLockById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    private void opposeAllSupportedPlayers(Long userAccountId, LocalDateTime now) {
        userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(userAccountId)
                .forEach(support -> support.oppose(now));
    }

    // findAllById 는 없는 id 를 조용히 빼고 돌려주므로 개수 비교로만 누락을 감지할 수 있다
    // (호출 전에 중복이 제거돼 있어야 이 비교가 성립한다).
    private List<Player> findAllExisting(List<Long> distinctPlayerIds) {
        List<Player> players = playerRepository.findAllById(distinctPlayerIds);
        if (players.size() != distinctPlayerIds.size()) {
            throw new BusinessException(ErrorCode.PLAYER_NOT_FOUND);
        }
        return players;
    }

    // UserSupportPlayer.player 와 Player.team 이 둘 다 LAZY 라, fetch join 조회가 아니면
    // PlayerResponse.from 이 행마다 프록시를 깨워 N+1 이 된다. 정렬도 DB 가 한다.
    @Transactional(readOnly = true)
    public List<PlayerResponse> currentSupportedPlayers(Long userAccountId) {
        return userSupportPlayerRepository.findAllActiveWithPlayerAndTeam(userAccountId)
                .stream()
                .map(UserSupportPlayer::getPlayer)
                .map(PlayerResponse::from)
                .toList();
    }
}
