export type EvidenceValueString = {
  value: string;
  confidence: number;
  evidence: string[];
};

export type EvidenceValueDouble = {
  value: number;
  confidence: number;
  evidence: string[];
};

export type EvidenceTag = {
  tag: string;
  confidence: number;
  evidence: string[];
};

export type EvidenceDetail = {
  tag: string;
  evidenceString: string;
};

export type EvidenceNotes = {
  summary: string;
  evidence: EvidenceDetail[];
};

export type EvidenceScores = {
  energy: EvidenceValueDouble;
  valence: EvidenceValueDouble;
  night: EvidenceValueDouble;
  coding: EvidenceValueDouble;
  skipRisk: EvidenceValueDouble;
  danceability: EvidenceValueDouble;
  acousticness: EvidenceValueDouble;
  lyricDensity: EvidenceValueDouble;
  vocalPresence: EvidenceValueDouble;
  familiarity: EvidenceValueDouble;
  intensity: EvidenceValueDouble;
  speechiness: EvidenceValueDouble;
  instrumentalness: EvidenceValueDouble;
  liveness: EvidenceValueDouble;
  emotionalIntensity: EvidenceValueDouble;
  lyricalFocus: EvidenceValueDouble;
  mainstreamAppeal: EvidenceValueDouble;
};

export type TrackEvidenceState = {
  metadata: boolean;
  lyrics: boolean;
  audioFeatures: boolean;
  userBehavior: boolean;
  manual: boolean;
  model: boolean;
};

export type EvidenceTrackAnalysis = {
  provider: string;
  id: string;
  title: string;
  artist: string;
  album?: string | null;
  durationMs?: number | null;
  coverUrl?: string | null;
  playCount?: number | null;
  language: EvidenceValueString;
  moodTags: EvidenceTag[];
  contextTags: EvidenceTag[];
  soundTags: EvidenceTag[];
  useTags: EvidenceTag[];
  scores: EvidenceScores;
  evidence: TrackEvidenceState;
  lyricExcerpt?: string | null;
  notes?: string | null;
  analysisNotes?: EvidenceNotes | null;
  needsReview: boolean;
  lastAnalyzedAt?: string | null;
};

export type TrackScoresView = {
  energy: number;
  valence: number;
  night: number;
  coding: number;
  skipRisk: number;
  speechiness: number;
  emotionalIntensity: number;
  lyricalFocus: number;
  mainstreamAppeal: number;
};

export type TaggedTrackView = {
  provider: string;
  id: string;
  title: string;
  artist: string;
  album?: string | null;
  coverUrl?: string | null;
  language: string;
  dominantTags: string[];
  scores: TrackScoresView;
  confidence: number;
  needsReview: boolean;
  lastAnalyzedAt?: string | null;
};

export type TasteTracksResponse = {
  tracks: TaggedTrackView[];
  total: number;
};

export type TasteTagsResponse = {
  tags: string[];
};
