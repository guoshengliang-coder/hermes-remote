import { defineConfig } from "vite";
import { readFileSync } from "node:fs";
import preact from "@preact/preset-vite";

// Served by the Gateway under /app/ with a strict CSP (script-src 'self'; style-src 'self'), so
// nothing may be inlined: no modulepreload polyfill script, no data: assets.
export default defineConfig({
  base: "/app/",
  plugins: [preact(), {
    name: "shared-offline-table-chart",
    generateBundle() {
      for (const file of ["chart.html", "engine.js", "chart.js", "chart.css"]) {
        this.emitFile({ type: "asset", fileName: `charts/${file}`, source: readFileSync(new URL(`../android/app/src/main/assets/table-chart/${file}`, import.meta.url)) });
      }
    },
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const file = request.url?.split("?")[0]?.replace(/^\/app\/charts\//, "");
        if (!file || !["chart.html", "engine.js", "chart.js", "chart.css"].includes(file)) return next();
        response.setHeader("Content-Type", file.endsWith("html") ? "text/html" : file.endsWith("css") ? "text/css" : "text/javascript");
        response.end(readFileSync(new URL(`../android/app/src/main/assets/table-chart/${file}`, import.meta.url)));
      });
    },
  }],
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
