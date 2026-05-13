import { useEffect, useState } from "react";
import { AlertCircle, ChevronLeft, ChevronRight, Loader2, Trash2 } from "lucide-react";
import { radioApi } from "../../api";
import type { EvidenceTag, EvidenceTrackAnalysis, TaggedTrackView } from "../../types";

const LIBRARY_PAGE_LIMIT = 40;
const SCORE_PERCENT = 100;

type Props = {
  onError: (message: string | null) => void;
  refreshSignal?: number;
  onLibraryChanged?: () => void;
};

export function LibraryView({ onError, refreshSignal = 0, onLibraryChanged }: Props) {
  const [tracks, setTracks] = useState<TaggedTrackView[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [language, setLanguage] = useState("");
  const [tag, setTag] = useState("");
  const [sort, setSort] = useState("recent");
  const [minConfidence, setMinConfidence] = useState("");
  const [selected, setSelected] = useState<EvidenceTrackAnalysis | null>(null);
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [tagOptions, setTagOptions] = useState<string[]>([]);

  useEffect(() => {
    setOffset(0);
  }, [language, tag, sort, minConfidence]);

  useEffect(() => {
    void loadTracks();
  }, [language, tag, sort, minConfidence, offset, refreshSignal]);

  useEffect(() => {
    void loadTagOptions();
  }, [refreshSignal]);

  async function loadTagOptions() {
    try {
      const response = await radioApi.tasteTags();
      setTagOptions(response.tags);
    } catch (event) {
      console.warn("Could not load tag list.", event);
    }
  }

  async function loadTracks() {
    setLoading(true);
    try {
      const response = await radioApi.tasteTracks({
        language,
        tag,
        sort,
        minConfidence,
        limit: LIBRARY_PAGE_LIMIT,
        offset
      });
      setTracks(response.tracks);
      setTotal(response.total);
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Could not load library.");
    } finally {
      setLoading(false);
    }
  }

  async function selectTrack(track: TaggedTrackView) {
    setDetailLoading(true);
    try {
      setSelected(await radioApi.tasteTrack(track.provider, track.id));
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Could not load track evidence.");
    } finally {
      setDetailLoading(false);
    }
  }

  async function deleteTrack(track: EvidenceTrackAnalysis) {
    setDetailLoading(true);
    try {
      await radioApi.deleteTasteTrack(track.provider, track.id);
      setSelected(null);
      await loadTracks();
      await loadTagOptions();
      onLibraryChanged?.();
      onError(null);
    } catch (event) {
      onError(event instanceof Error ? event.message : "Track evidence could not be deleted.");
    } finally {
      setDetailLoading(false);
    }
  }

  const canPrevious = offset > 0;
  const canNext = offset + LIBRARY_PAGE_LIMIT < total;

  return (
    <section className="view-surface library-view" aria-label="Analyzed library">
      <div className="view-heading">
        <div>
          <span>Library</span>
          <h1>Every analyzed track, with its evidence visible.</h1>
        </div>
        <strong>{total} tracks</strong>
      </div>

      <div className="library-tools" aria-label="Library filters">
        <select value={language} onChange={(event) => setLanguage(event.target.value)} aria-label="Language">
          <option value="">All languages</option>
          <option value="en">English</option>
          <option value="zh-CN">Mandarin</option>
          <option value="zh">Chinese</option>
          <option value="yue">Cantonese</option>
          <option value="ja">Japanese</option>
          <option value="ko">Korean</option>
          <option value="unknown">Unknown</option>
        </select>
        <select value={tag} onChange={(event) => setTag(event.target.value)} aria-label="Tag">
          <option value="">All tags</option>
          {tagOptions.map((item) => <option key={item} value={item}>{item}</option>)}
        </select>
        <select value={sort} onChange={(event) => setSort(event.target.value)} aria-label="Sort">
          <option value="recent">Recent</option>
          <option value="confidence">Confidence</option>
          <option value="artist">Artist</option>
          <option value="energy">Energy</option>
          <option value="night">Night</option>
          <option value="coding">Coding</option>
        </select>
        <input
          value={minConfidence}
          onChange={(event) => setMinConfidence(event.target.value)}
          inputMode="decimal"
          placeholder="min confidence"
          aria-label="Minimum confidence"
        />
      </div>

      <div className="library-layout">
        <div className="track-table-wrap">
          <table className="track-table">
            <thead>
              <tr>
                <th>Track</th>
                <th>Lang</th>
                <th>Energy</th>
                <th>Night</th>
                <th>Coding</th>
                <th>Confidence</th>
              </tr>
            </thead>
            <tbody>
              {tracks.map((track) => (
                <tr key={`${track.provider}-${track.id}`} onClick={() => void selectTrack(track)}>
                  <td>
                    <div className="track-cell">
                      {track.coverUrl ? <img src={track.coverUrl} alt="" /> : <span />}
                      <div>
                        <strong>{track.title}</strong>
                        <small>{track.artist}</small>
                      </div>
                    </div>
                  </td>
                  <td>{track.language}</td>
                  <td>{score(track.scores.energy)}</td>
                  <td>{score(track.scores.night)}</td>
                  <td>{score(track.scores.coding)}</td>
                  <td>{score(track.confidence)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {loading ? <p className="muted-line"><Loader2 className="spin" size={15} /> Loading tracks...</p> : null}
          {!loading && !tracks.length ? <p className="muted-line">No analyzed tracks match these filters.</p> : null}
          <div className="pager">
            <button type="button" onClick={() => setOffset(Math.max(0, offset - LIBRARY_PAGE_LIMIT))} disabled={!canPrevious}>
              <ChevronLeft size={16} />
              Previous
            </button>
            <span>{offset + 1}-{Math.min(offset + LIBRARY_PAGE_LIMIT, total)} of {total}</span>
            <button type="button" onClick={() => setOffset(offset + LIBRARY_PAGE_LIMIT)} disabled={!canNext}>
              Next
              <ChevronRight size={16} />
            </button>
          </div>
        </div>

        <TrackDetail track={selected} loading={detailLoading} onDelete={() => selected ? void deleteTrack(selected) : undefined} />
      </div>
    </section>
  );
}

function TrackDetail({ track, loading, onDelete }: {
  track: EvidenceTrackAnalysis | null;
  loading: boolean;
  onDelete: () => void;
}) {
  if (loading) return <aside className="detail-panel"><Loader2 className="spin" size={18} /> Loading evidence...</aside>;
  if (!track) return <aside className="detail-panel muted-line">Select a track to inspect its evidence.</aside>;
  return (
    <aside className="detail-panel">
      <div className="detail-title">
        <div>
          <span>{track.provider}</span>
          <h2>{track.title}</h2>
          <p>{track.artist}</p>
        </div>
        <div className="detail-actions">
          {track.needsReview ? <AlertCircle size={18} /> : null}
          <button type="button" className="icon-danger-button" onClick={onDelete} aria-label={`Delete ${track.title}`}>
            <Trash2 size={16} />
          </button>
        </div>
      </div>
      <section>
        <h3>Signals</h3>
        <div className="score-grid">
          <ScoreBar label="energy" value={track.scores.energy.value} />
          <ScoreBar label="valence" value={track.scores.valence.value} />
          <ScoreBar label="night" value={track.scores.night.value} />
          <ScoreBar label="coding" value={track.scores.coding.value} />
          <ScoreBar label="skip risk" value={track.scores.skipRisk.value} />
          <ScoreBar label="speech" value={track.scores.speechiness.value} />
          <ScoreBar label="lyrical" value={track.scores.lyricalFocus.value} />
          <ScoreBar label="emotional" value={track.scores.emotionalIntensity.value} />
          <ScoreBar label="mainstream" value={track.scores.mainstreamAppeal.value} />
        </div>
      </section>
      <TagGroup title="Mood" tags={track.moodTags} />
      <TagGroup title="Context" tags={track.contextTags} />
      <TagGroup title="Sound" tags={track.soundTags} />
      <TagGroup title="Use" tags={track.useTags} />
      <section>
        <h3>Evidence</h3>
        <p className="muted-line">
          metadata {yesNo(track.evidence.metadata)} · lyrics {yesNo(track.evidence.lyrics)} · model {yesNo(track.evidence.model)}
        </p>
        {track.analysisNotes?.summary ? <p>{track.analysisNotes.summary}</p> : track.notes ? <p>{track.notes}</p> : null}
        {track.analysisNotes?.evidence.length ? (
          <div className="evidence-list">
            {track.analysisNotes.evidence.map((item) => (
              <p key={`${item.tag}-${item.evidenceString}`}>
                <strong>{item.tag}</strong>
                {item.evidenceString}
              </p>
            ))}
          </div>
        ) : null}
      </section>
    </aside>
  );
}

function TagGroup({ title, tags }: { title: string; tags: EvidenceTag[] }) {
  if (!tags.length) return null;
  return (
    <section>
      <h3>{title}</h3>
      <div className="tag-cloud">
        {tags.map((tag) => (
          <span key={tag.tag}>{tag.tag} · {score(tag.confidence)}</span>
        ))}
      </div>
    </section>
  );
}

function ScoreBar({ label, value }: { label: string; value: number }) {
  return (
    <div className="score-bar">
      <span>{label}</span>
      <div><i style={{ width: `${Math.round(value * SCORE_PERCENT)}%` }} /></div>
    </div>
  );
}

function score(value: number): string {
  return `${Math.round(value * SCORE_PERCENT)}%`;
}

function yesNo(value: boolean): string {
  return value ? "yes" : "no";
}
