import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// GitHub Pages 처럼 하위 경로(https://…/repo/)에 올릴 때만 base 를 바꾼다.
// Vercel · Netlify · S3 루트 배포는 '/' 그대로 두면 된다.
export default defineConfig({
  base: '/',
  plugins: [react()],
  server: { port: 5500, open: true },
});
