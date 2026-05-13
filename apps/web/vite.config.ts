import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const radioProxy = {
  target: "http://127.0.0.1:8080",
  changeOrigin: true,
  configure(proxy) {
    proxy.on("proxyReq", (proxyReq) => {
      proxyReq.removeHeader("origin");
    });
  }
};

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": radioProxy,
      "/media": radioProxy
    }
  }
});
