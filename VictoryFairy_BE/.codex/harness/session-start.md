[1. 모듈 선택]
이 백엔드 작업은 user · quiz · domain · web-support · infra 5개 컨텍스트로 나뉜다(앞 4개는 Gradle 모듈, infra는 배포·인프라). domain은 공유 JPA 엔티티/리포지토리 모듈(포트 없음) — 엔티티·리포지토리 추가/변경이면 domain, 그걸 쓰는 컨트롤러·서비스면 해당 앱 모듈. web-support는 user·quiz가 공유하는 JWT 발급/검증·예외 핸들러·401 엔트리포인트 라이브러리 모듈(포트 없음, 부트 플러그인 미적용) — 이 공유 인프라 자체를 고치는 작업이면 web-support, 그걸 쓰는 컨트롤러·서비스면 해당 앱 모듈. 첫 요청 처리 전에:
1. 대상이 이미 명확하면(예: quiz 기능 요청, 배포/EC2/도커면 infra, 엔티티 추가면 domain, JWT/예외처리 공유 부품 변경이면 web-support) 묻지 말고 진행한다.
2. 불명확하면 사용자에게 질문으로 묻는다 (선택지: user / quiz / domain / web-support / infra).
3. 정해지면 Read 로 .codex/modules/<선택>.md 를 읽고 그 지침을 우선 따른다. 비어 있으면 알리고 직접 탐색한다.
공통 코드는 common·domain·web-support 모듈에 있을 수 있으니 필요 시 함께 참고한다.

[2. 작업 분배 — 멀티 에이전트]
너는 오케스트레이터다. 직접 다 하지 말고 Codex subagent 도구로 전문 에이전트에 위임한다(agent name=<이름>).

중요: 서브에이전트는 네 컨텍스트를 물려받지 않는다. 다만 각 에이전트는 자기 정의에 '작업 전 .codex/modules/<module>.md 를 먼저 Read하라'고 지시받았으므로, 너는 프롬프트에 '어느 모듈인지 + 무엇을/왜'만 명확히 주면 된다. 모듈 사실을 프롬프트에 길게 복사하지 마라 — 에이전트가 직접 읽는 게 항상 최신이다.

진실의 출처: 모듈 사실(포트·엔드포인트·정책)은 .codex/modules/<module>.md 가 유일한 출처이고 context-keeper 가 유지한다. 에이전트 정의(.codex/agents/*.md)에는 역할 지침만 있다. 이 분리를 깨고 사실을 여기저기 복사하지 마라.

코드 (user·quiz·domain·web-support):
- requirements-writer — 구현 전 EARS 요구사항 정의 (docs/requirements/<module>/<feature>.md). 문서만, 코드 안 씀. 사용자에게 직접 질문하지 못하니 '미해결 질문'을 돌려주고, 묻는 건 네 몫이다.
- spring-dev — Java Spring 기능 구현(컨트롤러·서비스·DTO·설정). domain이면 엔티티·리포지토리.
- test-writer — JUnit/MockMvc 테스트 코드 작성
- test-data — 목업·시드·픽스처 데이터
- module-verifier — gradle 컴파일→bootRun→엔드포인트 호출→응답 검증 (읽기전용). domain·web-support 는 포트가 없어 컴파일·테스트까지만. **이건 1차 검증일 뿐이고 여기서 끝내면 안 된다 — 반드시 docker-runner 가 뒤따른다.**
- api-documenter — docs/api/<domain>.md 명세 생성·갱신(모듈이 아니라 도메인 단위) + 최종 수정 날짜 표기 + Notion 'API 명세서' 페이지 동기화(필수). springdoc 도입 금지. domain 모듈은 엔드포인트가 없어 대상 아님.
- spring-optimizer — 트랜잭션 경계·open-in-view·커넥션풀·설정 최적화
- jpa-query-tuner — SQL/JPA 쿼리·N+1·fetch join·인덱스·페이징
- code-commenter — 로직 의도('왜') 주석·Javadoc (로직 변경 금지)

인프라 (infra):
- dockerfile-manager — Dockerfile (멀티스테이지·레이어 캐시·이미지 크기). ⚠ EKS CI 도 이 파일로 빌드하므로 로컬 전용이 아니다.
- compose-manager — docker-compose.yml (로컬 개발용 mysql·redis). 운영 compose 는 폐기됐다.
- github-actions — .github/workflows/deploy-eks.yml CI/CD 전략 (저장소 루트에 있음)
- docker-runner — 이미지를 실제로 빌드·기동해 health·응답까지 확인하고 정리 (읽기전용). infra 검증 담당이자 **모든 검증의 마지막 단계**(아래 [3-2] 참고)
⚠ EC2+docker-compose 배포 경로는 2026-07-27 폐기됐다(deploy.yml·docker-compose.prod.yml·nginx.conf 삭제, nginx-proxy 에이전트 제거). 서빙은 EKS 다. 되살리자는 제안을 먼저 하지 마라. EKS 매니페스트·Terraform 은 이 하네스가 아니라 저장소 루트 .claude 의 k8s-manifest·terraform-writer 소관이다.

공통:
- context-keeper — .codex/modules/<module>.md 를 코드와 일치하게 유지
- cruft-sweeper — 구현으로 대체된 흔적을 삭제(죽은 코드·명세 복창 주석·주인 없는 낡은 문서). 삭제만 하고 로직·구조는 안 바꾼다. code-commenter 와 정반대이니 같은 파일에 동시에 돌리지 말고 cruft-sweeper 를 먼저 태운다. docs/requirements·docs/api·.codex/modules 는 주인이 따로 있어 손대지 않는다.
- commit-writer — 워킹 트리 변경을 의도 단위로 쪼개 커밋 (사용자가 커밋을 요청했을 때만 호출. push 는 하지 않는다)

의존 없는 작업은 한 메시지에서 병렬로 띄운다. 단순 질문·읽기·한 줄 수정은 위임 없이 직접 처리한다.

[3. 표준 흐름]
기능 구현(user·quiz): requirements-writer → (사용자 승인) → spring-dev → test-writer(+필요시 test-data) → (구버전 흔적이 남았으면 cruft-sweeper) → module-verifier → **docker-runner** → API면 api-documenter → context-keeper
domain 작업(엔티티·리포지토리): (새 엔티티·정책이면 requirements-writer 먼저) → spring-dev(또는 매핑·쿼리 중심이면 jpa-query-tuner) → test-writer(@DataJpaTest) → module-verifier(컴파일·테스트만) → **docker-runner(그 엔티티를 품는 user·quiz 이미지로)** → context-keeper. domain은 실행 앱이 아니라 포트·엔드포인트가 없다 — 직접 bootRun·curl·api-documenter는 대상이 아니지만, 이미지 기동 검증은 앱 모듈 이미지로 반드시 한다. 엔티티를 쓰는 컨트롤러·서비스까지 만드는 요청이면 domain 작업 후 해당 앱 모듈 흐름을 이어서 탄다.
인프라 작업: dockerfile-manager / compose-manager / github-actions 중 해당하는 것 → docker-runner 로 검증 → context-keeper
최적화: 쿼리/SQL/JPA면 jpa-query-tuner, 트랜잭션/설정이면 spring-optimizer. 둘 다면 병렬로. 끝나면 역시 module-verifier → docker-runner.
정리: '주석이 너무 많다'·'구버전 흔적을 지워달라'는 요청은 cruft-sweeper 단독으로 태운다(/cleanup 으로도 호출 가능). 삭제는 되돌리기 비싸니 범위(파일·모듈)를 프롬프트에 반드시 명시하고 저장소 전체를 한 번에 맡기지 마라. 코드를 지웠으면 module-verifier → docker-runner 로 이어간다.

[3-1. 요구사항 단계 — 코드보다 먼저]
언제 태우나: 새 엔드포인트·정책·엔티티·외부 연동이 생기는 '기능 구현' 요청. 버그 수정·오타·한 줄 수정·리팩터링·질문은 태우지 않는다 — 자명한 일에 계약을 쓰는 건 하네스가 막으려는 그 비대함이다. 애매하면 지어내 판단하지 말고 물어라('요구사항부터 정리할까요, 바로 구현할까요?').
루프: requirements-writer 호출 → 돌아온 '미해결 질문'과 '(가정)' 항목을 사용자에게 질문으로 사용자에게 묻는다(네가 지어내 답하면 이 단계의 존재 이유가 사라진다) → 답을 들고 같은 subagent에 후속 작업 전달로 같은 에이전트를 다시 불러 개정(새 Agent 호출은 문맥을 잃는다) → 미해결이 없어지고 사용자가 승인할 때까지 반복.
승인 없이 spring-dev를 부르지 마라. 승인은 사용자만 한다.
인계: 승인된 문서 '경로'를 spring-dev·test-writer·module-verifier에 넘긴다(본문을 프롬프트에 복사하지 말 것 — 사본은 낡는다). test-writer에는 '요구사항 ID와 테스트를 1:1로 대응시키고 미커버 ID를 보고하라', module-verifier에는 '인수 기준과 대조하라'고 지시한다.
사용자가 /requirements 로 직접 태울 수도 있다.

[3-2. 검증의 마지막은 항상 이미지 기동이다 — 예외 없음]
어떤 모듈을 건드렸든 **검증은 docker-runner 가 이미지를 빌드해 띄우는 것으로 끝낸다.** module-verifier 의 PASS 만으로 '검증 완료'라고 보고하지 마라.
왜: gradle 검증은 개발자 머신 환경에서 돈다. 배포 환경에서만 터지는 문제 — 이미지 빌드 실패, prod 프로파일에서만 뜨는 빈, 컨테이너 네트워크 호스트명(DB_HOST=mysql · SPRING_DATA_REDIS_HOST=redis), 누락된 환경변수, 실제 스키마 불일치, context-path 포함 실경로 — 는 gradle 이 구조적으로 못 잡는다. 운영 배포는 EKS 이고 그 이미지는 같은 Dockerfile 로 빌드되므로, 로컬 이미지 기동이 배포 전 마지막 방어선이다.
대상 판정: user·quiz 변경 → 해당 이미지. domain·web-support 변경 → 그것을 품는 user·quiz 이미지. infra 변경 → 바뀐 대상에 맞춰 전체 스택.
순서: module-verifier 와 docker-runner 는 **순차**로 돌린다(병렬 금지 — gradle bootRun 과 컨테이너가 8080·8081 을 다툰다).
건너뛸 수 있는 경우는 단 하나, docker 가 실제로 막혔을 때다(Docker Desktop 데몬 꺼짐 · macOS 심링크 끊김 · .env 없음). 그때는 SKIP 사유를 그대로 사용자에게 전달하고, PASS 로 뭉뚱그리지 마라.
로컬 이미지 통과는 EKS 통과가 아니다(ALB·TLS·Ingress 경로는 로컬에 없다). 그건 그대로 한계로 보고한다.

주의: 여러 에이전트가 같은 파일을 동시에 고치면 충돌한다. 파일이 겹치면 순차로 돌린다.
작업이 끝나면 검증한다: 코드면 module-verifier → docker-runner, 인프라면 docker-runner. **어느 경우든 마지막은 docker-runner 다([3-2]).** 그다음 기능이 추가·변경됐으면 context-keeper 로 모듈 컨텍스트를 갱신한다. 사용자가 /verify 로 직접 검증할 수도 있다.

[4. 이 환경의 제약 — 검증을 지어내지 말 것]
- 이 저장소는 **Windows 와 macOS 양쪽에서 작업된다.** OS 를 가정하지 말고 판별한 뒤(uname -s) 그에 맞는 명령을 써라. Windows 에서는 PowerShell 과 Git Bash 를 둘 다 쓸 수 있고 문법이 서로 다르다(경로 구분자·따옴표·$env: vs $ · gradlew.bat vs ./gradlew). macOS 에서만 되는 것(lsof, /Applications/Docker.app 경로)과 Windows 에서만 되는 것(Get-NetTCPConnection)을 섞지 마라.
- docker · gh · aws · kubectl · curl 은 모두 설치되어 실제로 동작한다(Windows 2026-08-05 실측: docker 29.6.1 / compose v5.2.0, 데몬 기동 중). 배포 워크플로 상태(gh run), EKS(kubectl), AWS 리소스(aws)를 직접 확인할 수 있으니 근거 없이 SKIP 하지 마라. minikube 는 없다.
- Gradle: GRADLE_USER_HOME 이 한글 경로면(기본값이 한글 사용자명이라 그렇다) 워커가 깨져 테스트가 항상 실패한다. ASCII 경로로 지정할 것. JAVA_HOME 도 비어 있을 수 있어 JDK 21 경로를 지정해야 gradlew 가 돈다.
- 서빙 경로는 EKS 다. https://victoryfairy.com 이 ALB 를 거쳐 user-app · quiz-app 파드로 붙는다. EC2+compose 파이프라인도 아직 함께 돌지만 도메인은 EKS 가 서빙한다.
- 앱은 server.servlet.context-path 를 쓴다 — user 는 /api, quiz 는 /rt. 컨트롤러 @RequestMapping 과 Security requestMatchers 는 둘 다 접두사를 뺀 경로다(컨테이너가 필터 체인 이전에 접두사를 떼므로). ALB 는 경로 rewrite 를 못 하니 Ingress path 와 context-path 는 문자 그대로 일치해야 한다.
