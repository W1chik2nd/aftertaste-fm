import { resolveMediaUrl } from "../api";
import type { QueueItem } from "../types";

/**
 * Returns the URL the main <audio> element should play for this queue item.
 *
 * Host voice rows can come in two shapes:
 *   - chapter-lead host break (has a `track` AND its streamUrl): main player plays the lead track,
 *     and the host TTS is overlaid via the voice element.
 *   - text-only host break (no playable track): main player plays the TTS audio itself.
 */
export function mainMediaUrl(item: QueueItem | null | undefined): string | undefined {
  if (!item) return undefined;
  if (item.type === "track") return resolveMediaUrl(item.track?.streamUrl);
  return resolveMediaUrl(item.track?.streamUrl ?? item.ttsUrl);
}

/**
 * Returns the URL for the overlay <audio> element used to speak over the chapter lead.
 * Only non-null when this is a host_voice row that DOES have a playable lead track —
 * otherwise the main element is already playing the TTS.
 */
export function voiceOverlayUrl(item: QueueItem | null | undefined): string | undefined {
  if (!item) return undefined;
  if (item.type !== "host_voice") return undefined;
  if (!item.track?.streamUrl) return undefined;
  return resolveMediaUrl(item.ttsUrl);
}
