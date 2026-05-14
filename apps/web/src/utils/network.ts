export const OFFLINE_LIVE_ACTION_MESSAGE =
  "You are offline. Live radio controls need the server; cached station data can still be viewed.";

export const OFFLINE_MEDIA_MESSAGE =
  "You are offline. Audio streams are live-only, so playback will resume when the network is back.";

export function isBrowserOffline() {
  return typeof navigator !== "undefined" && navigator.onLine === false;
}
