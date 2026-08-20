package com.skhynix.user.profileimage.storage;

import java.time.Instant;

/**
 * 목록 조회가 돌려주는 오브젝트 1건 — 정리 판정에 실제로 쓰는 두 값뿐이다.
 *
 * <p>SDK 타입({@code S3Object})을 그대로 흘리지 않는 이유는 호출자가 S3 를 알지 않아도 되게 하기
 * 위해서다(정리 서비스는 "키와 마지막 수정 시각"만 알면 된다).
 */
public record StoredObject(String key, Instant lastModified) {
}
