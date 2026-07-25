import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  // Absolute, not "./": the SPA is served from the domain root (see
  // frontend/nginx.conf) and has routes more than one segment deep, e.g.
  // /reference-data/:setCode. With a relative base, a cold load or refresh on
  // such a route resolves "./assets/..." against the route directory and 404s,
  // leaving a blank page. Only change this if the app ever moves to a sub-path.
  base: "/",
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    outDir: "dist",
    sourcemap: false,
  },
});
