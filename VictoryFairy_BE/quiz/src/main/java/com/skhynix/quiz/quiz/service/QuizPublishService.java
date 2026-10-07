package com.skhynix.quiz.quiz.service;

import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.team.repository.TeamRepository;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매일 편성 잡. "그날 세트"를 두 쿼터로 나눠 채운다 — 전역 단일 캡으로는 어떤 팀이 그날 얼마나
 * 받는지 전혀 보장하지 못했다(id 오름차순으로만 긁으면 먼저 쌓인 소수 팀 문항이 캡을 다 먹는다).
 *
 * <ul>
 *   <li><b>팀 쿼터</b>({@code quiz.serve.daily-count-per-team}) — {@code teams} 테이블의 각 팀마다
 *       독립적으로 "그 팀이 {@code team} 또는 {@code opponentTeam}으로 들어간 오늘 문항"이 목표치에
 *       못 미치면 부족분만큼 그 팀 한정 미편성 풀에서 채운다. 10개 KBO 구단을 하드코딩하지 않고
 *       {@code TeamRepository}를 그대로 순회한다 — teams 가 곧 화이트리스트다({@code teams-init.sql}).</li>
 *   <li><b>공통 쿼터</b>({@code quiz.serve.daily-count-common}) — {@code team}이 비어 있는(완전
 *       공통) 문항 전용. 팀 쿼터와 풀을 공유하지 않으므로(각 쿼리가 {@code team_id} 조건으로
 *       갈린다) 서로 잠식하지 않는다.</li>
 * </ul>
 *
 * <p>맞대결·경기 문항({@code team}·{@code opponentTeam} 둘 다 참)은 양팀 쿼터 계산에 각각 한 번씩
 * "이미 채워진 것"으로 세어지지만, 실제 {@code quiz_date} 갱신은 {@code WHERE quiz_date IS NULL}
 * 가드 덕에 먼저 처리된 팀의 호출에서 1회만 일어난다(상세는 {@link QuizRepository#publishFromPoolForTeam}).
 */
@Service
public class QuizPublishService {

    private static final Logger log = LoggerFactory.getLogger(QuizPublishService.class);

    private final QuizRepository quizRepository;
    private final TeamRepository teamRepository;
    private final int dailyCountCommon;
    private final int dailyCountPerTeam;

    public QuizPublishService(QuizRepository quizRepository, TeamRepository teamRepository,
            @Value("${quiz.serve.daily-count-common:10}") int dailyCountCommon,
            @Value("${quiz.serve.daily-count-per-team:200}") int dailyCountPerTeam) {
        this.quizRepository = quizRepository;
        this.teamRepository = teamRepository;
        this.dailyCountCommon = dailyCountCommon;
        this.dailyCountPerTeam = dailyCountPerTeam;
    }

    @Transactional
    public int publishDaily(LocalDate today) {
        int published = publishCommon(today);
        for (Team team : teamRepository.findAllByOrderByNameAsc()) {
            published += publishForTeam(today, team);
        }
        return published;
    }

    private int publishCommon(LocalDate today) {
        long existing = quizRepository.countByQuizDateAndTeamIsNull(today);
        int deficit = (int) (dailyCountCommon - existing);
        int published = deficit > 0 ? quizRepository.publishCommonFromPool(today, deficit) : 0;
        log.info("오늘의 퀴즈 편성: 공통 기존 {}건 + 신규 {}건 (목표 {}건, {})",
                existing, published, dailyCountCommon, today);
        return published;
    }

    private int publishForTeam(LocalDate today, Team team) {
        long existing = quizRepository.countByQuizDateAndTeam(today, team.getId());
        int deficit = (int) (dailyCountPerTeam - existing);
        int published = deficit > 0
                ? quizRepository.publishFromPoolForTeam(today, team.getId(), deficit)
                : 0;
        log.info("오늘의 퀴즈 편성: {}({}) 기존 {}건 + 신규 {}건 (목표 {}건, {})",
                team.getName(), team.getCode(), existing, published, dailyCountPerTeam, today);
        return published;
    }
}
