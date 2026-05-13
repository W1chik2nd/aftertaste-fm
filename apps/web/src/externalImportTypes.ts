export type ImportEvidenceJsonResponse = {
  importedTrackCount: number;
  ignoredDuplicateCount: number;
  totalTrackCount: number;
  sourceName?: string | null;
  qualityWarnings: string[];
};

export type DeleteImportResponse = {
  slug: string;
  deleted: boolean;
  deletedTrackEvidenceCount: number;
};

export type DeleteTrackEvidenceResponse = {
  provider: string;
  id: string;
  deleted: boolean;
};
