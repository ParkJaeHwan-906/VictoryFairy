package com.skhynix.user.game.realtime;

import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.user.game.dto.GameResponse;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQS 가 알린 경기를 DB 에서 다시 읽어 {@link GameUpdateEvent}로 만든다.
 *
 * <p>트랜잭션이 필수다 — {@code GameResponse.from}이 구단·구장·상태 연관을 읽는데 prod 는
 * {@code open-in-view: false}이고, 이 호출은 어차피 요청 스레드가 아니라 리스너 스레드다. 연관은
 * {@code GameRepository.findWithDetailsByNaverGameId}의 {@code @EntityGraph}가 한 번에 가져온다(N+1 없음).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameUpdateService {

    private final GameRepository gameRepository;

    /** 경기 행이 없으면(수집기보다 먼저 지워졌거나 알 수 없는 id) 빈 Optional — 호출부가 건너뛴다. */
    public Optional<GameUpdateEvent> load(GameStateEventDocument document) {
        List<String> changed = document.changed() == null ? List.of() : List.copyOf(document.changed());
        return gameRepository.findWithDetailsByNaverGameId(document.gameId())
                .map(game -> new GameUpdateEvent(GameResponse.from(game), changed, document.observedAt()));
    }
}
