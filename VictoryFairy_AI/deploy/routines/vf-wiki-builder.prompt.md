# routine 프롬프트 — `vf-wiki-builder`

- **정본은 이 파일이다.** claude.ai 콘솔의 프롬프트는 이 파일의 복사본이며,
  콘솔에서 직접 고치지 않는다. 고칠 일이 생기면 이 파일을 PR로 바꾸고 나서
  콘솔에 붙여 넣는다(등록 절차: 같은 디렉토리 `README.md` 2번 섹션).
- **트리거 ID**: `trig_01Wr5oZofsjorgduWfgjbyML`
- **크론**: `0 21 * * 1,4` (UTC) = 화·금 06:00 KST
- **모델**: Sonnet 5 / **환경**: `vf-question-creation`
- 위키의 원본은 **`VictoryFairy_WIKI` 리포의 `dev` 브랜치**다. 루틴이 직접
  클론·커밋·푸시한다 — S3에 위키 사본을 두지 않는다(2026-08-06 구조 단순화).

---

```
VictoryFairy LLM 위키 빌더 루틴이다 (화·금 주 2회).

[자격·보안]
- AWS 자격증명은 환경변수로 주입되어 있다. 값 출력·파일 기록 금지. 버킷은 $S3_BUCKET (입력 크롤 데이터 읽기 전용).
- GitHub 쓰기는 이 계정에 연결된 자격증명을 그대로 쓴다. 토큰 값을 출력하거나 파일에 적지 마라.

[준비]
1. git fetch origin sotaeho/ai/feat-llm-wiki-quiz && git checkout sotaeho/ai/feat-llm-wiki-quiz 로 파이프라인 브랜치로 전환하라. (이 브랜치가 dev_ai에 머지되면 이 단계는 제거된다)
2. cd VictoryFairy_AI 후 wiki-builder/ROUTINE.md 를 읽고 '사전 조건'부터 검증하라. 도구가 없으면 설치하라(python3 -m pip install --quiet awscli PyYAML 등 — 명시적 허용).

[실행]
3. ROUTINE.md 절차를 0단계(위키 클론)부터 순서대로 수행하라. 위키는 s3가 아니라 VictoryFairy_WIKI 리포 dev 브랜치를 .work/wiki-repo/ 로 클론해서 읽고 고친다.
4. 0단계 클론이 실패하면 즉시 중단하라 — 기존 문서 없이 병합하면 누적된 위키를 통째로 날린다.
5. 7단계에서 .work/wiki-repo 안에서 wiki/ 를 커밋하고 origin dev 로 push 한다. 푸시가 거부되면 git pull --rebase origin dev 후 한 번 재시도하라.

[규칙]
- 사전 조건 폴백 설치 외 단계 실패는 해당 문서를 생략하고(fail-closed) 마지막 응답에 실패 지점·에러 전문을 보고하라.
- 세션이 시작한 리포(VictoryFairy)에는 커밋·push 하지 마라. 쓰기 대상은 VictoryFairy_WIKI 의 dev 뿐이다.
- 위키 리포의 main 브랜치는 건드리지 마라. force push 금지.
- LLM 금지: 수치 계산 금지, 근거 없는 서술 금지, 사람 검수 시드 임의 수정 금지.

[보고]
- 마지막 응답에: 갱신/스킵 문서 목록과 사유, 커밋 해시와 푸시 결과를 표로 출력하라.
```

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-04 | 위키 원본을 S3 → VictoryFairy_WIKI `dev`로 이전, outbox 반출 규칙 반영 |
| 2026-08-05 | 프롬프트를 리포로 이관해 정본화 |
| 2026-08-06 | **outbox 폐기** — 루틴이 `dev`에 직접 커밋·푸시. 계정 GitHub 자격증명에 write가 생겨(`/web-setup`) 우회로가 불필요해졌다 |
