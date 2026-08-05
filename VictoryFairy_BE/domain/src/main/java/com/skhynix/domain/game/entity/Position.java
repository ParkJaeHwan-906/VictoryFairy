package com.skhynix.domain.game.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 수비 포지션 코드 테이블. 값은 <b>화면에 그대로 나가는 정식 명칭</b>이다
 * ({@code GameLineupResponse.positionName} 이 이 값을 가공 없이 내보낸다).
 * py-collector 가 네이버 record API 박스스코어의 {@code pos} 표기를 아래 매핑으로
 * 변환해 lookup-or-insert 하며, {@link GameLineup}이 {@code position_id} FK 로 참조한다.
 *
 * <p>네이버 {@code pos} 표기 → 적재값 (py-collector {@code db.py POSITION_NAMES} 와 동일):
 * <pre>
 *   투 → 투수      포 → 포수      一 → 1루수     二 → 2루수
 *   三 → 3루수     유 → 유격수    좌 → 좌익수    중 → 중견수
 *   우 → 우익수    지 → 지명타자  타 → 대타      주 → 대주자
 * </pre>
 * 1루/2루/3루는 네이버가 한자(一/二/三)로 보낸다. 대타/대주자는 수비 위치가 아니라
 * 출전 형태임에 주의.
 *
 * <p><b>교체 출전은 시작 위치로 접힌다.</b> 한 경기에서 수비 위치를 바꾼 선수를
 * 네이버 박스스코어는 표기를 이어붙여 보낸다({@code 중좌}=중견수→좌익수,
 * {@code 타二}=대타→2루수, {@code 三유}=3루수→유격수). py-collector
 * {@code position_name} 이 <b>첫 글자 = 그 경기 시작 위치</b>로 접어 적재하므로
 * 조합 표기는 DB 에 들어오지 않는다 — 라인업 화면이 "이 선수가 어디로 나왔나"를
 * 보여주기 때문이고, 접지 않으면 조합이 12x12 까지 늘어 이 테이블이 계속 불어난다.
 * 접기 이전에 쌓인 값(영문 약어 12종 + 조합 58종)은
 * {@code infra/sql/migrate-position-names.sql} 이 위 12종으로 병합한다.
 *
 * <p><b>이 값을 소비하는 쪽에 대한 경고:</b> {@code positions.name}은 닫힌 enum이
 * 아니다. 첫 글자조차 매핑에 없는 미지 표기는 수집이 깨지지 않도록 원문 그대로
 * 적재되므로(py-collector 는 warning 만 남긴다), 위 12종만 분기하고 default 를
 * 두지 않는 코드는 새 표기가 나오면 깨진다.
 */
@Entity
@Table(name = "positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Position(String name) {
        this.name = name;
    }
}
