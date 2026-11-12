import { defineConfig } from 'vite';

// base: './' keeps asset URLs relative. Games are served under a sub-path
// (/preview/<projectId>/ and /game/ during playtests); do not change it.
export default defineConfig({
  base: './',
});
