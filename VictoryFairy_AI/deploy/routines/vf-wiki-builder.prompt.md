# routine 프롬프트 — `vf-wiki-builder`

- **정본은 이 파일이다.** claude.ai 콘솔의 프롬프트는 이 파일의 복사본이며,
  콘솔에서 직접 고치지 않는다. 고칠 일이 생기면 이 파일을 PR로 바꾸고 나서
  콘솔에 붙여 넣는다(등록 절차: 같은 디렉토리 `README.md` 2번 섹션).
- **트리거 ID**: `trig_01Wr5oZofsjorgduWfgjbyML`
- **크론**: `0 21 * * 1,4` (UTC) = 화·금 06:00 KST
- **모델**: Sonnet 5 / **환경**: `vf-question-creation`
- 위키 원본은 S3가 아니라 **VictoryFairy_WIKI 리포의 `dev` 브랜치**다. 루틴은
  git push 권한이 없으므로(실측 확인) 갱신분을 S3 `wiki-outbox/`로 반출하고,
  `VictoryFairy_WIKI`의 `wiki-sync` 워크플로(화·금 07:30 KST)가 그걸 `dev`에
  커밋한 뒤 S3 `wiki/` 읽기 캐시를 역동기화한다.

---

```
VictoryFairy LLM 위키 빌더 루틴이다 (화·금 주 2회).

[자격·보안]
- AWS 자격증명은 환경변수로 주입되어 있다. 값 출력·파일 기록 금지. 버킷은 $S3_BUCKET.

[위키 원본 — 중요]
- 위키의 진실의 원천은 이제 S3가 아니라 **VictoryFairy_WIKI 리포의 dev 브랜치**다.
1. git clone --depth 1 -b dev https://github.com/ParkJaeHwan-906/VictoryFairy_WIKI.git /tmp/wiki 로 기존 위키를 읽어라 (public 리포).
2. 이 세션 리포(VictoryFairy)에서는 git fetch origin sotaeho/ai/feat-llm-wiki-quiz && git checkout sotaeho/ai/feat-llm-wiki-quiz 후 VictoryFairy_AI/wiki-builder/ROUTINE.md 절차를 따른다. 사전 조건 도구가 없으면 설치하라(python3 -m pip install --quiet awscli PyYAML 등 — 명시적 허용). ROUTINE.md가 "S3 wiki/ 동기화"를 말하는 부분은 /tmp/wiki의 복사본으로 대체한다.

[쓰기 — outbox 규칙]
3. 갱신·신규 문서는 S3 wiki/ 가 아니라 **s3://$S3_BUCKET/wiki-outbox/** 아래에 wiki/ 기준 상대경로 그대로 업로드한다 (예: players/52605.md → wiki-outbox/players/52605.md, graph.json → wiki-outbox/graph.json, _meta/builder-runs/... 동일). 변경된 파일만 올린다.
4. S3 wiki/ 에는 직접 쓰지 마라 — Actions(wiki-sync)가 outbox를 dev에 커밋하고 캐시도 갱신한다.
- 한글은 UTF-8, 업로드 시 content-type에 charset=utf-8 지정.

[규칙]
- 사전 조건 폴백 설치 외 단계 실패는 해당 문서를 생략하고(fail-closed) 마지막 응답에 실패 지점·에러 전문을 보고하라.
- git push·커밋 금지 (세션 푸시는 불가능).
- LLM 금지: 수치 계산 금지, 근거 없는 서술 금지, 사람 검수 시드 임의 수정 금지.

[보고]
- 마지막 응답에: 갱신/스킵 문서 목록과 사유, wiki-outbox에 올린 파일 목록을 표로 출력하라.
```

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-04 | 위키 원본을 S3 → VictoryFairy_WIKI `dev`로 이전, outbox 반출 규칙 반영 |
| 2026-08-05 | 프롬프트를 리포로 이관해 정본화 |
