export type LyricLine = {
  id: string;
  time: number | null;
  text: string;
};

const TIME_PATTERN = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g;
const META_PATTERN = /^\[(ar|al|ti|by|offset|kana|tool):/i;

export function parseLyrics(raw: string | null): LyricLine[] {
  if (!raw) return [];
  const timed: LyricLine[] = [];
  const plain: string[] = [];

  raw.split(/\r?\n/).forEach((line, lineIndex) => {
    const trimmed = line.trim();
    if (!trimmed || META_PATTERN.test(trimmed)) return;

    const matches = [...trimmed.matchAll(TIME_PATTERN)];
    const text = trimmed.replace(TIME_PATTERN, "").trim();
    if (!matches.length) {
      if (text) plain.push(text);
      return;
    }
    if (!text) return;

    matches.forEach((match, matchIndex) => {
      const minutes = Number(match[1]);
      const seconds = Number(match[2]);
      const fraction = match[3] ? Number(`0.${match[3].padEnd(3, "0").slice(0, 3)}`) : 0;
      timed.push({
        id: `${lineIndex}-${matchIndex}-${minutes}-${seconds}`,
        time: minutes * 60 + seconds + fraction,
        text
      });
    });
  });

  if (timed.length) return timed.sort((left, right) => (left.time ?? 0) - (right.time ?? 0));
  return plain.map((text, index) => ({ id: `plain-${index}`, time: null, text }));
}

export function activeLyricLineIndex(lines: LyricLine[], currentTime: number): number {
  if (!lines.some((line) => line.time != null)) return -1;
  let active = 0;
  for (let index = 0; index < lines.length; index += 1) {
    const time = lines[index].time;
    if (time == null) continue;
    if (time <= currentTime + 0.25) active = index;
    else break;
  }
  return active;
}
