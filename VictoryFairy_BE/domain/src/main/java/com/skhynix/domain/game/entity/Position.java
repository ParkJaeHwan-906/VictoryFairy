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
 * 수비 포지션 코드 테이블. 값은 자체 영문 약어다. py-collector 가 네이버 record API
 * 박스스코어의 {@code pos} 표기를 아래 매핑으로 변환해 lookup-or-insert 하며,
 * {@link GameLineup}이 {@code position_id} FK 로 참조한다.
 *
 * <p>네이버 {@code pos} 표기 ↔ 약어 매핑 (py-collector {@code db.py POSITION_CODES} 와 동일):
 * <pre>
 *   투 → P  (투수)      포 → C  (포수)      一 → 1B (1루수)     二 → 2B (2루수)
 *   三 → 3B (3루수)     유 → SS (유격수)    좌 → LF (좌익수)    중 → CF (중견수)
 *   우 → RF (우익수)    지 → DH (지명타자)  타 → PH (대타)      주 → PR (대주자)
 * </pre>
 * 1루/2루/3루는 네이버가 한자(一/二/三)로 보낸다. PH/PR 는 수비 위치가 아니라
 * 출전 형태임에 주의.
 *
 * <p><b>약어 매핑은 예외가 아니라 소수다.</b> 2026-08-04 dev DB {@code positions}
 * 전수 조회 기준 총 70행 중 위 12종에 매핑된 값은 12종뿐이고, 나머지 58종(83%)은
 * 한글·한자 원문이 그대로 적재돼 있다. 이 58종은 무작위 미지 표기가 아니라
 * 전부 "단일 표기 2개를 이어붙인 2글자"라는 규칙을 따른다 — 한 경기에서 수비
 * 위치를 바꾼(교체 출전) 선수를 네이버 박스스코어가 이렇게 표기하기 때문이다
 * (예: {@code 주좌}=주(대주자)+좌(좌익수), {@code 二一}=二(2루수)+一(1루수),
 * {@code 타지}=타(대타)+지(지명타자)). py-collector {@code POSITION_CODES}가
 * 단일 문자만 인식해 2글자 조합은 변환에 걸리지 않고 원문 그대로 저장된다.
 * 조합은 단일 표기 12개의 순서쌍으로 체계적으로 생성되므로 발견할 때마다
 * 양쪽 매핑에 하나씩 추가하는 방식은 맞지 않다 — 근본 대응(2글자 분해 후 변환)은
 * py-collector 쪽 결정 사항이며 이 엔티티의 책임 밖이다.
 *
 * <p><b>이 값을 소비하는 쪽에 대한 경고:</b> {@code positions.name}은 닫힌 enum이
 * 아니다. 위 12종만 분기하고 default를 두지 않는 코드는 교체 출전 선수에서 깨진다.
 * 위 58종은 2026-08-04 관측 범위이며, 수집이 계속되면 조합이 더 늘어날 수 있으니
 * 이 목록을 닫힌 것으로 신뢰하지 말 것. 3글자 이상 표기가 나올 가능성도 코드로
 * 막혀 있지는 않으나, 관측된 적은 없다.
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
