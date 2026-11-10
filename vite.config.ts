import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => ({
  base: mode === 'github-pages' ? '/SkyCop---Autonomous-Drone-Based-Traffic-Monitoring/' : '/',
  plugins: [react()],
}));