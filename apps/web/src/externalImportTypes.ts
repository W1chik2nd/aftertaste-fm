export type ImportEvidenceJsonResponse = {
  importedTrackCount: number;
  ignoredDuplicateCount: number;
  totalTrackCount: number;
  sourceName?: string | null;
};
