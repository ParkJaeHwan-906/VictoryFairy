package com.skhynix.user.game.dto;

import com.skhynix.domain.game.entity.GameLineup;
import java.util.List;

public record GameLineupResponse(Long teamId,
                                 List<Pitcher> pitchers,
                                 List<Batter> batters) {

    public record Pitcher(String name, String positionName) {

        public static Pitcher from(GameLineup lineup) {
            return new Pitcher(lineup.getPlayer().getName(), positionNameOf(lineup));
        }
    }

    public record Batter(String name, String positionName, Integer batOrder) {

        public static Batter from(GameLineup lineup) {
            return new Batter(lineup.getPlayer().getName(), positionNameOf(lineup), lineup.getBatOrder());
        }
    }

    private static String positionNameOf(GameLineup lineup) {
        return lineup.getPosition() == null ? null : lineup.getPosition().getName();
    }
}
