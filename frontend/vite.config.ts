import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => ({
  // The hosted SPA is served from the domain root. The packaged Electron app
  // loads index.html through file:// and therefore needs relative assets; an
  // absolute /assets URL points at the filesystem root and leaves a blank
  // renderer before React can mount.
  base: mode === "desktop" ? "./" : "/",
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    outDir: "dist",
    sourcemap: false,
  },
}));
