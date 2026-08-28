package com.skhynix.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 사용자가 꾸미는 아바타 캐릭터. 지금은 '승리요정' 한 행뿐이지만, 캐릭터가 늘어나도 코드 변경 없이
 * 행만 추가하면 되도록 {@code CharacterItem} 이 이 테이블을 FK 로 참조한다(캐릭터마다 입힐 수 있는
 * 아이템이 다르다).
 *
 * <p>⚠ 클래스 이름이 {@code java.lang.Character} 와 겹친다. 같은 패키지에 있거나 이 타입을 import 한
 * 파일에서는 {@code Character} 가 이 엔티티로 해석되므로, 그런 파일에서 문자 래퍼가 필요하면
 * {@code java.lang.Character} 로 완전 수식해야 한다. 그럼에도 이 이름을 쓰는 것은 "클래스는 테이블명의
 * 단수형"이라는 domain 모듈 규약을 따른 결과다({@code characters} → {@code Character}).
 */
@Entity
@Table(
        name = "characters",
        // 제약 이름을 명시한다(Hibernate 자동 생성명 UK... 대신) — 나중에 손으로 도는 DDL 과 같은
        // 이름을 써야 "이미 걸렸는지"를 이름으로 확인할 수 있다(uk_quiz_type_name 과 같은 성격).
        uniqueConstraints = @UniqueConstraint(name = "uk_characters_name", columnNames = "name"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Character {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 캐릭터 이름. 시드({@code character-asset-init.sql})가 이 값 기준 anti-join 으로 재실행 안전을
     * 확보하므로 UNIQUE 가 없으면 기동할 때마다 같은 캐릭터가 한 행씩 늘어난다.
     */
    @Column(name = "name", length = 50, nullable = false)
    private String name;

    /**
     * 캐릭터 이미지의 <b>EP</b> — BaseURL 을 뺀 S3 오브젝트 키다(예 {@code characters/victory-fairy.svg}).
     * 프로필 이미지({@code users_account.profile_img_url})와 문자 그대로 같은 형태이며, 클라이언트는
     * {@code https://victoryfairy.com/} + 값 한 가지 조립 규칙만 알면 된다.
     *
     * <p>절대 URL 을 넣지 말 것 — 도메인·CDN 이 바뀌면 전 행을 UPDATE 해야 한다.
     */
    @Column(name = "img", length = 255, nullable = false)
    private String img;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Character(String name, String img) {
        this.name = name;
        this.img = img;
    }
}
