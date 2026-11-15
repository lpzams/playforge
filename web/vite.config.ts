/// <reference types="vitest" />
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

const backend = 'http://localhost:8090';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/preview': { target: backend, changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
  },
});
