import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ApiError, getTeamList, isSupportTeamNotFound, selectSupportTeam } from '../api';
import type { Team } from '../api';
import ConfirmSheet from '../components/ConfirmSheet';
import { getTeamDisplay } from '../data/kboTeams';
import { ROUTES, readSupportFlowMode, type PlayerSelectState } from '../routes';
import { useAccountStore, useSupportTeam } from '../stores/useAccountStore';
import '../styles/TeamSelectPage.css';

/**
 * TeamSelectPage — "선호 구단 선택" 화면.
 * Figma: SWM / [Onboarding] 선호 구단 선택-기본 (node 296:1536)
 *              [Onboarding] 선호 구단 선택-선택완료 (node 406:3387)
 *
 * 두 노드는 별개 화면이 아니라 같은 화면의 두 상태다 — 하나를 고르면
 * 고른 카드만 강조되고 나머지는 흐려지며, 하단 CTA 가 활성화된다.
 *
 * ── 온보딩과 수정이 같은 화면을 쓴다 ──────────────────────────────────
 * 마이페이지 "○○ 응원중!" 에서도 이 화면으로 들어온다(`mode: 'edit'`). 화면도 API 도
 * 같고 다른 것은 셋뿐이다 — 지금 응원 중인 구단이 미리 선택돼 있고, **구단을 실제로 바꿀 때
 * 한 번 더 묻고**, 저장 뒤 스토어를 갱신한다. 다음 화면(선수 선택)으로 가는 것은 같다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 🚨 **구단이 바뀌면 그 계정의 응원 선수가 전원 자동 취소된다**(docs/support.md).
 * API 응답에는 그 사실을 알리는 필드가 없어 화면이 책임진다 — 바꾸기 전에 확인 시트로
 * 알리고, 저장 뒤 프로필을 다시 받아 비워진 선수 목록을 스토어에 반영한다.
 */

/**
 * 구단 변경 확인 시트 문구.
 * 줄은 디자인이 끊어 둔 그대로 넘긴다(`ConfirmSheet` 의 `description` 주석 참고).
 */
const TEAM_CHANGE_CONFIRM = {
  title: '응원 구단을 바꿀까요?',
  description: ['구단을 바꾸면 지금 응원 중인', '선수 선택이 모두 해제돼요.'],
  confirmLabel: '구단 바꾸기',
  pendingLabel: '저장 중…',
} as const;

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
  const mode = readSupportFlowMode(useLocation().state);
  const isEdit = mode === 'edit';
  /** 지금 응원 중인 구단. 수정 흐름에서 미리 선택해 두고, 바뀌었는지 판정하는 기준이다. */
  const currentTeam = useSupportTeam();
  const fetchProfile = useAccountStore((state) => state.fetchProfile);

  const [teams, setTeams] = useState<Team[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  /** 저장 실패 사유. CTA 바로 위에 띄운다(목록 로딩 실패와 자리가 다르다). */
  const [submitError, setSubmitError] = useState<string | null>(null);
  /** 값을 올릴 때마다 구단 목록을 다시 받는다 — 저장이 404(없는 구단)로 떨어졌을 때 쓴다. */
  const [reloadKey, setReloadKey] = useState(0);
  /** 구단 변경 확인 시트를 띄울지. 수정 흐름에서 **실제로 구단이 바뀔 때만** 뜬다. */
  const [isConfirming, setIsConfirming] = useState(false);

  /*
   * 수정 흐름은 지금 응원 중인 구단이 선택된 채로 시작한다 — 무엇을 바꾸는 중인지
   * 보이지 않으면 고르기 전과 후를 구별할 수 없다.
   * 새로고침으로 이 주소에 바로 들어오면 프로필이 아직 없을 수 있어 도착한 뒤에 채우고,
   * 사용자가 이미 다른 구단을 골랐다면 늦게 온 응답이 그것을 덮지 않도록 한 번만 넣는다.
   */
  const [isSeeded, setIsSeeded] = useState(false);
  useEffect(() => {
    if (!isEdit || isSeeded || currentTeam === null) return;

    setSelectedId(currentTeam.id);
    setIsSeeded(true);
  }, [isEdit, isSeeded, currentTeam]);

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
      /*
       * 수정 흐름은 여기서 프로필을 다시 받는다.
       * 구단이 바뀌었다면 서버가 응원 선수를 전원 취소한 뒤라, 다음 화면이 채워 넣을
       * "지금 응원 중인 선수"도 그 이후의 값이어야 한다. 갱신하지 않고 넘기면
       * 이미 해제된 선수들이 선택된 것처럼 보인다.
       */
      if (isEdit) await fetchProfile();

      const state: PlayerSelectState = { teamId: team.id, teamName: team.name, mode };

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
      /* 시트를 닫아야 그 아래 화면의 오류 문구가 보인다 */
      setIsConfirming(false);
    }

    // 성공 경로에서는 되돌리지 않는다 — 화면이 넘어갈 때까지 CTA 를 잠가 둔다.
  };

  const rows = chunkIntoRows(teams, ROW_SIZES);
  const hasSelection = selectedId !== null;
  /**
   * 지금 고른 것이 응원 구단을 **실제로 바꾸는** 선택인지.
   *
   * 같은 구단을 다시 고른 경우는 서버가 아무것도 건드리지 않으므로(멱등) 묻지 않는다 —
   * 응원 선수가 지워지는 것은 구단이 달라졌을 때뿐이다.
   */
  const isTeamChanging =
    isEdit && currentTeam !== null && hasSelection && selectedId !== currentTeam.id;

  /** CTA. 응원 선수가 함께 지워지는 경우에만 한 번 더 묻고, 나머지는 곧장 저장한다. */
  const handleSubmitClick = () => {
    if (!hasSelection || isSubmitting) return;

    if (isTeamChanging) {
      setIsConfirming(true);
      return;
    }

    void handleSubmit();
  };

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
        onClick={handleSubmitClick}
        disabled={!hasSelection || isSubmitting}
        aria-busy={isSubmitting}
      >
        {isSubmitting ? '저장 중...' : '다음으로'}
      </button>

      {isConfirming && (
        <ConfirmSheet
          {...TEAM_CHANGE_CONFIRM}
          isPending={isSubmitting}
          onConfirm={() => void handleSubmit()}
          onClose={() => setIsConfirming(false)}
        />
      )}
    </div>
  );
}
