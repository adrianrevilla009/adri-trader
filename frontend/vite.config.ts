/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// In development the screen calls "/api" on its own origin and Vite forwards it to the backend on the loopback
// address, so the browser never needs CORS and the token never leaves this machine.
export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: { "/api": { target: process.env.VITE_BACKEND_URL ?? "http://127.0.0.1:8100", changeOrigin: false } },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/setupTests.ts"],
    css: false,
  },
});
