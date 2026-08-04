package com.skhynix.domain.player.entity;

/**
 * KBO 공식 등록명단의 포지션 구분(4그룹).
 *
 * <p>KBO 공식 소스(등록명단·선수검색·선수상세)는 세부 수비 포지션(1루수/유격수 등)을 제공하지
 * 않고 이 4그룹이 전부다. 세부 주포지션이 필요해지면 {@code game_lineups} 의 경기별 포지션을
 * 집계해 파생하는 것이 다음 단계다(별도 컬럼, 이 enum 의 확장이 아니라).
 *
 * <p>py-collector 가 raw SQL 로 이름 문자열({@code 'PITCHER'} 등)을 그대로 적재하므로
 * {@link jakarta.persistence.EnumType#STRING} 매핑을 전제로 한다 — ORDINAL 로 바꾸면
 * 수집기와의 계약이 깨진다.
 */
public enum PositionGroup {
    PITCHER,
    CATCHER,
    INFIELDER,
    OUTFIELDER,
}
