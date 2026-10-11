import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv, type ProxyOptions } from 'vite';

/**
 * dev 서버 프록시.
 *
 * 운영은 CloudFront 한 도메인 아래 FE·API 가 같은 오리진이라 CORS 설정이 없다 — 그래서
 * `localhost:5173` 에서 운영 API 를 직접 부르면 preflight 가 막혀 브라우저에는 네트워크
 * 에러로만 보인다. dev 에서는 base URL 을 상대 경로(`/api`·`/rt`·`/chat`)로 두고
 * 이 프록시가 대신 운영(또는 `VITE_DEV_PROXY_TARGET`)으로 넘긴다.
 *
 * 브라우저가 붙인 `Origin: http://localhost:5173` 을 그대로 넘기면 서버가 교차 출처
 * 요청으로 보고 거절할 수 있어 지운다(같은 오리진 요청처럼 보이게).
 */
const PROXY_PREFIXES = ['/api', '/rt', '/chat'];

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.VITE_DEV_PROXY_TARGET || 'https://victoryfairy.com';

  const proxyOptions: ProxyOptions = {
    target,
    changeOrigin: true,
    secure: true,
    configure: (proxy) => {
      proxy.on('proxyReq', (proxyReq) => {
        proxyReq.removeHeader('origin');
      });
    },
  };

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: Object.fromEntries(PROXY_PREFIXES.map((prefix) => [prefix, proxyOptions])),
    },
  };
});
