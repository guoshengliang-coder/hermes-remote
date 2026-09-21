import { defineConfig } from "vite";
import preact from "@preact/preset-vite";

// Served by the Gateway under /app/ with a strict CSP (script-src 'self'; style-src 'self'), so
// nothing may be inlined: no modulepreload polyfill script, no data: assets.
export default defineConfig({
  base: "/app/",
  plugins: [preact()],
  build: {
    outDir: "dist",
    assetsDir: "assets",
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
    sourcemap: false,
    rollupOptions: {
      output: {
        entryFileNames: "assets/[name]-[hash].js",
        chunkFileNames: "assets/[name]-[hash].js",
        assetFileNames: "assets/[name]-[hash][extname]",
      },
    },
  },
});
