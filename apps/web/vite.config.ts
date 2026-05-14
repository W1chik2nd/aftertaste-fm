import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

const THEME_COLOR = "#000000";
const BACKGROUND_COLOR = "#000000";
const API_NETWORK_TIMEOUT_SECONDS = 5;
const API_CACHE_MAX_ENTRIES = 50;
const API_CACHE_MAX_AGE_SECONDS = 86_400;
const IMAGE_CACHE_MAX_ENTRIES = 100;
const IMAGE_CACHE_MAX_AGE_SECONDS = 2_592_000;

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
  plugins: [
    react(),
    VitePWA({
      registerType: "prompt",
      includeAssets: ["icons/apple-touch-icon.png"],
      manifest: {
        name: "Aftertaste FM",
        short_name: "Aftertaste",
        description: "A private AI radio for turning taste, context, and a small music provider into hosted listening sessions.",
        start_url: "/",
        scope: "/",
        display: "standalone",
        orientation: "any",
        theme_color: THEME_COLOR,
        background_color: BACKGROUND_COLOR,
        lang: "en",
        icons: [
          {
            src: "/icons/icon-192.png",
            sizes: "192x192",
            type: "image/png",
            purpose: "any"
          },
          {
            src: "/icons/icon-512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "any"
          },
          {
            src: "/icons/maskable-512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "maskable"
          }
        ]
      },
      workbox: {
        navigateFallback: "index.html",
        cleanupOutdatedCaches: true,
        globPatterns: ["**/*.{js,css,html,woff2,png,svg,webmanifest}"],
        runtimeCaching: [
          {
            urlPattern: ({ url }) => url.pathname.startsWith("/api/"),
            method: "GET",
            handler: "NetworkFirst",
            options: {
              cacheName: "api-cache",
              networkTimeoutSeconds: API_NETWORK_TIMEOUT_SECONDS,
              expiration: {
                maxEntries: API_CACHE_MAX_ENTRIES,
                maxAgeSeconds: API_CACHE_MAX_AGE_SECONDS
              },
              cacheableResponse: {
                statuses: [200]
              }
            }
          },
          {
            urlPattern: ({ url }) => url.pathname.startsWith("/media/"),
            method: "GET",
            handler: "NetworkOnly"
          },
          {
            urlPattern: ({ request, url }) => request.destination === "image" && !url.pathname.startsWith("/media/"),
            method: "GET",
            handler: "CacheFirst",
            options: {
              cacheName: "image-cache",
              expiration: {
                maxEntries: IMAGE_CACHE_MAX_ENTRIES,
                maxAgeSeconds: IMAGE_CACHE_MAX_AGE_SECONDS
              },
              cacheableResponse: {
                statuses: [0, 200]
              }
            }
          }
        ]
      },
      devOptions: {
        enabled: true,
        suppressWarnings: true
      }
    })
  ],
  server: {
    port: 5173,
    proxy: {
      "/api": radioProxy,
      "/media": radioProxy
    }
  }
});
