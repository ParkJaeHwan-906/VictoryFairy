package com.skhynix.user.account.dto;

import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.character.dto.EquippedCharacterItemResponse;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.team.dto.TeamResponse;
import java.math.BigDecimal;
import java.util.List;

// UserAccount 를 그대로 직렬화하면 password 해시·uid 가 함께 나간다 — 노출 키는 이 10개로 닫아 둔다.
// supportPlayers 는 응원 API 와 같은 PlayerResponse 재사용이라, 그쪽을 고치면 이 응답도 함께 바뀐다.
// profileImgUrl 은 BaseURL 을 뺀 EP 다(업로드 응답과 문자 그대로 같은 형태). 이미지가 없으면 null 이며
// 빈 문자열도 기본 이미지 URL 도 아니다 — supportTeam 이 null 인 것과 같은 방식이다.
// 이 record 는 /me 전용이라 필드 추가가 파괴적이지 않다(여러 곳이 공유하는 PlayerResponse 와 다르다).
// characterImgUrl 은 프로필 사진이 아니라 아바타 캐릭터의 이미지 EP 다 — 둘은 별개이며 서로를
// 대체하지 않는다. 캐릭터를 못 받은 계정은 null + 빈 배열이고, 그때도 200 이다(supportTeam 과 같은 안전망).
// quizAccuracy 가 BigDecimal 인 이유는 자릿수가 아니라 표기다 — double 이면 값이 0·1 일 때 0.0·1.0 으로
// 나가고, 후행 0 을 떼어 낸 값(0.5)을 그대로 실을 수단이 없다. 반올림·후행 0 제거는 서비스가 끝내고
// 여기서는 받은 값을 그대로 담는다(스케일을 3 으로 고정하지 않는다 — 패딩은 프론트엔드 몫이다).
// bqRank 가 Integer 인 이유는 "구단 없음"을 null 로 나타내기 위해서다 — 0 은 순위가 아니고, 키를 빼면
// 프론트가 supportTeam 을 먼저 봐야 한다. /rankings/bq/me 의 rank 와 같은 값이다.
public record UserAccountResponse(String nickname, TeamResponse supportTeam,
                                  List<PlayerResponse> supportPlayers, long point, long bqScore,
                                  String profileImgUrl, String characterImgUrl,
                                  List<EquippedCharacterItemResponse> characterItems,
                                  BigDecimal quizAccuracy, Integer bqRank) {

    public static UserAccountResponse of(UserAccount account, TeamResponse supportTeam,
                                         List<PlayerResponse> supportPlayers, long bqScore,
                                         String characterImgUrl,
                                         List<EquippedCharacterItemResponse> characterItems,
                                         BigDecimal quizAccuracy, Integer bqRank) {
        return new UserAccountResponse(
                account.getNickname(),
                supportTeam,
                supportPlayers,
                account.getPoint(),
                bqScore,
                // 이미 조회한 users_account 행에 들어 있는 값이라 SELECT 가 늘지 않는다.
                account.getProfileImgUrl(),
                characterImgUrl,
                characterItems,
                quizAccuracy,
                bqRank);
    }
}
