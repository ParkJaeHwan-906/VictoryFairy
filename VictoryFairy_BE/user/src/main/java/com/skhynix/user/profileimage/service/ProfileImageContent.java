package com.skhynix.user.profileimage.service;

import com.skhynix.user.profileimage.policy.ProfileImageFormat;

/**
 * 검증을 통과한 업로드 바이트와 그 <b>판정된</b> 형식. 검증과 저장 사이를 잇는 내부 값이다.
 *
 * <p>둘을 함께 들고 다니는 이유: 확장자와 저장 Content-Type 이 모두 이 {@code format} 에서 나와야
 * 하는데, 저장 시점에 다시 판정하면 같은 바이트를 두 번 읽게 되고 두 판정이 갈릴 여지가 생긴다.
 */
record ProfileImageContent(byte[] bytes, ProfileImageFormat format) {
}
