# 1단계: Build stage — Vite 프로덕션 번들 생성
FROM node:24-slim AS builder

WORKDIR /app

# ⚠ API base URL 은 Vite 가 번들에 문자열로 '박아버린다'. 런타임 주입이 불가능해
#   ConfigMap/환경변수로는 바꿀 수 없고, 값이 달라지면 이미지를 다시 구워야 한다.
#   미지정 시 src/api/config.ts 의 localhost:8080 폴백이 그대로 박혀서 배포는 성공하고
#   브라우저에서 API 호출만 전부 실패한다 — 빌드가 깨지지 않으니 놓치기 쉽다.
ARG VITE_API_USER_BASE_URL
ARG VITE_API_GAME_BASE_URL
ENV VITE_API_USER_BASE_URL=${VITE_API_USER_BASE_URL}
ENV VITE_API_GAME_BASE_URL=${VITE_API_GAME_BASE_URL}

# 의존성 설치를 소스 복사보다 앞에 둬서, 소스만 바뀐 빌드는 npm ci 레이어를 재사용하게 한다.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build


# 2단계: Runtime stage — 정적 파일만 남긴다(node 런타임·의존성 없음)
#
# nginx-unprivileged 는 비루트(uid 101)로 뜨고 8080 을 듣는다. 비루트 프로세스는 80 을
# 열 수 없으므로 포트를 바꾼 것이며, nginx.conf 의 listen·k8s Service 의 targetPort 가
# 모두 8080 으로 일치해야 한다.
FROM nginxinc/nginx-unprivileged:1.29-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 8080
