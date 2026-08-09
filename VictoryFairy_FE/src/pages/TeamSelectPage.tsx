import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, getTeamList, isSupportTeamNotFound, selectSupportTeam } from '../api';
import type { Team } from '../api';
import { getTeamDisplay } from '../data/kboTeams';
import { ROUTES, type PlayerSelectState } from '../routes';
import '../styles/TeamSelectPage.css';

/**
 * TeamSelectPage — 온보딩 "선호 구단 선택" 화면.
 * Figma: SWM / [Onboarding] 선호 구단 선택-기본 (node 296:1536)
 *              [Onboarding] 선호 구단 선택-선택완료 (node 406:3387)
 *
 * 두 노드는 별개 화면이 아니라 같은 화면의 두 상태다 — 하나를 고르면
 * 고른 카드만 강조되고 나머지는 흐려지며, 하단 CTA 가 활성화된다.
 */

/**
 * 디자인의 줄 배치(2 · 3 · 3 · 2). 균등 그리드가 아니라 가운데로 모이는
 * 의도된 배치라 그대로 옮긴다. 구단 수가 10 개보다 많으면 남는 만큼 3 개씩 잇는다.
 */
const ROW_SIZES = [2, 3, 3, 2];

function chunkIntoRows<T>(items: T[], sizes: number[]): T[][] {
  const rows: T[][] = [];
  let index = 0;

  for (const size of sizes) {
    if (index >= items.length) break;
    rows.push(items.slice(index, index + size));
    index += size;
  }
  while (index < items.length) {
    rows.push(items.slice(index, index + 3));
    index += 3;
  }

  return rows;
}

export default function TeamSelectPage() {
  const navigate = useNavigate();

  const [teams, setTeams] = useState<Team[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  /** 저장 실패 사유. CTA 바로 위에 띄운다(목록 로딩 실패와 자리가 다르다). */
  const [submitError, setSubmitError] = useState<string | null>(null);
  /** 값을 올릴 때마다 구단 목록을 다시 받는다 — 저장이 404(없는 구단)로 떨어졌을 때 쓴다. */
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    // 화면을 벗어난 뒤 늦게 도착한 응답으로 상태를 건드리지 않도록 막는다.
    let alive = true;

    setIsLoading(true);
    setLoadFailed(false);

    getTeamList()
      .then((list) => {
        if (alive) setTeams(list);
      })
      .catch(() => {
        if (alive) setLoadFailed(true);
      })
      .finally(() => {
        if (alive) setIsLoading(false);
      });

    return () => {
      alive = false;
    };
  }, [reloadKey]);

  /**
   * 응원 구단 저장 → 선수 선택.
   *
   * `POST /support/team` 은 최초 선택·변경·재선택을 모두 처리하므로 현재 상태를 먼저
   * 조회할 필요가 없다. 응답으로 반영 후 구단(`{ id, name }`)이 돌아와 그대로 다음
   * 화면에 넘긴다 — 선수 검색이 이 구단으로 좁혀져야 하기 때문이다.
   *
   * 이 API 는 인증이 필수다(access 토큰으로 대상 계정을 정한다). 401 은 httpClient
   * 인터셉터가 재발급까지 시도하고, 그래도 실패하면 여기 catch 로 온다.
   */
  const handleSubmit = async () => {
    if (selectedId === null || isSubmitting) return;

    setIsSubmitting(true);
    setSubmitError(null);

    try {
      const team = await selectSupportTeam(selectedId);
      const state: PlayerSelectState = { teamId: team.id, teamName: team.name };

      navigate(ROUTES.playerSelect, { state });
    } catch (error: unknown) {
      // 404 는 화면이 들고 있는 목록이 서버와 어긋났다는 뜻이라, 문구보다 목록을 다시 받는 게 먼저다.
      if (isSupportTeamNotFound(error)) {
        setSelectedId(null);
        setReloadKey((key) => key + 1);
        setSubmitError('구단 목록이 갱신되었어요. 다시 선택해주세요.');
      } else {
        setSubmitError(
          error instanceof ApiError
            ? error.message
            : '구단을 저장하지 못했어요. 잠시 후 다시 시도해주세요.',
        );
      }

      setIsSubmitting(false);
    }

    // 성공 경로에서는 되돌리지 않는다 — 화면이 넘어갈 때까지 CTA 를 잠가 둔다.
  };

  const rows = chunkIntoRows(teams, ROW_SIZES);
  const hasSelection = selectedId !== null;

  return (
    <div className="team-select-page">
      <header className="team-select-page__topbar">
        <button
          className="team-select-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="team-select-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="team-select-page__topbar-title">구단 선택</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="team-select-page__topbar-spacer" aria-hidden="true" />
      </header>

      <p className="team-select-page__heading">
        응원하고 있는 구단을
        <br />
        선택해주세요
      </p>

      <div className="team-select-page__body">
        {isLoading && <p className="team-select-page__status">구단 목록을 불러오는 중입니다.</p>}

        {loadFailed && (
          <p className="team-select-page__status team-select-page__status--error">
            구단 목록을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        )}

        {!isLoading && !loadFailed && (
          <fieldset className="team-select-page__grid">
            <legend className="team-select-page__legend">응원 구단</legend>

            {rows.map((row, rowIndex) => (
              <div className="team-select-page__row" key={rowIndex}>
                {row.map((team) => {
                  const display = getTeamDisplay(team.name);
                  const isSelected = team.id === selectedId;
                  // 하나라도 고른 뒤에는 고르지 않은 카드가 흐려진다(선택완료 상태).
                  const isDimmed = hasSelection && !isSelected;

                  return (
                    <label
                      className={[
                        'team-select-page__card',
                        isSelected ? 'team-select-page__card--selected' : '',
                        isDimmed ? 'team-select-page__card--dimmed' : '',
                      ]
                        .filter(Boolean)
                        .join(' ')}
                      key={team.id}
                    >
                      <input
                        className="team-select-page__radio"
                        type="radio"
                        name="support-team"
                        value={team.id}
                        checked={isSelected}
                        onChange={() => setSelectedId(team.id)}
                      />
                      <span className="team-select-page__logo-box">
                        {display && (
                          <img className="team-select-page__logo" src={display.logo} alt="" />
                        )}
                      </span>
                      <span className="team-select-page__name">{display?.label ?? team.name}</span>
                    </label>
                  );
                })}
              </div>
            ))}
          </fieldset>
        )}
      </div>

      {submitError && (
        <p
          className="team-select-page__status team-select-page__status--error team-select-page__submit-error"
          role="alert"
        >
          {submitError}
        </p>
      )}

      <button
        className="team-select-page__submit"
        type="button"
        onClick={handleSubmit}
        disabled={!hasSelection || isSubmitting}
        aria-busy={isSubmitting}
      >
        {isSubmitting ? '저장 중...' : '다음으로'}
      </button>
    </div>
  );
}
