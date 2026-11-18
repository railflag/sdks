import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm', 'cjs'],
  target: 'es2020',
  platform: 'browser',
  dts: true,
  external: [/^@openfeature\//],
  clean: true,
});
