import { defineConfig } from 'tsup';

// The published package bundles the private workspace packages (@railflag/eval, @railflag/schema)
// so users install one package. OpenFeature stays a peer and zod a dependency (the public types use it).
export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm', 'cjs'],
  target: 'node20',
  platform: 'node',
  dts: {
    // Workspace packages export .ts source; paths lets the declaration bundler inline their types.
    compilerOptions: { paths: { '@railflag/eval': ['../eval/src/index.ts'], '@railflag/schema': ['../schema/src/index.ts'] } },
  },
  noExternal: [/^@railflag\//],
  external: [/^@openfeature\//, 'zod'],
  clean: true,
});
