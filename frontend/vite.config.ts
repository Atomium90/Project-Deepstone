import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

export default defineConfig({
  plugins: [svelte()],
  server: {
    port: 5173,
    // Proxy WebSocket calls to the Scala backend during development
    proxy: {
      "/ws": {
        target: "ws://localhost:8080",
        ws: true,
      },
    },
  },
});