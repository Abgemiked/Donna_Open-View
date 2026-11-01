import { defineConfig } from 'vitest/config';
import { resolve } from 'path';

export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: [],
    include: ['src/**/__tests__/**/*.test.ts', 'src/**/*.test.ts'],
    alias: {
      '@renderer': resolve(__dirname, 'src/renderer/src'),
      '@donna/shared': resolve(__dirname, '../../packages/shared/src/api/index.ts'),
    },
  },
  resolve: {
    alias: {
      '@renderer': resolve(__dirname, 'src/renderer/src'),
      '@donna/shared': resolve(__dirname, '../../packages/shared/src/api/index.ts'),
    },
  },
});
