package com.skhynix.user.team.service;

import com.skhynix.domain.team.repository.TeamRepository;
import com.skhynix.user.team.dto.TeamResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamService {

    private final TeamRepository teamRepository;

    public List<TeamResponse> getTeams() {
        return teamRepository.findAllByOrderByNameAsc()
                .stream()
                .map(TeamResponse::from)
                .toList();
    }
}
