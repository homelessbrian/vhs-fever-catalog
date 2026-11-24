# VHS FEVER Program Assembly

## Purpose

A program is a logical viewing experience composed of one or more playable video segments. The original Internet Archive records remain unchanged. Program assembly adds relationships; it never merges, edits, or deletes source records.

## Three-layer model

1. **Archive item** — the original Internet Archive identifier and metadata record.
2. **Program** — the single title presented in the VHS FEVER library and player.
3. **Segment** — one exact playable file, identified by its archive item identifier and file name.

A program may reference segments from multiple archive items.

## Playback manifest

A program manifest should use this shape:

```json
{
  "schema_version": 1,
  "program_id": "vhsf-program-example",
  "title": "Example Saturday Morning Special",
  "assembly_status": "suggested",
  "match_confidence": "medium",
  "playback_mode": "sequential",
  "segments": [
    {
      "segment_id": "segment-001",
      "order": 1,
      "role": "opening",
      "archive_identifier": "archive-item-id",
      "file_name": "opening.mp4",
      "duration_seconds": 45
    },
    {
      "segment_id": "segment-002",
      "order": 2,
      "role": "main_program",
      "archive_identifier": "archive-item-id",
      "file_name": "feature.mp4",
      "duration_seconds": 1320
    },
    {
      "segment_id": "segment-003",
      "order": 3,
      "role": "bonus",
      "archive_identifier": "bonus-item-id",
      "file_name": "cartoon.mp4",
      "duration_seconds": 720
    },
    {
      "segment_id": "segment-004",
      "order": 4,
      "role": "closing",
      "archive_identifier": "archive-item-id",
      "file_name": "closing.mp4",
      "duration_seconds": 60
    }
  ],
  "total_known_duration_seconds": 2145,
  "evidence": [],
  "review_notes": []
}
```

The example is illustrative; identifiers and filenames must come from actual inventory records.

## Required invariants

- Each segment points to an exact archive identifier and exact file name.
- Segment order is explicit and stable; playback must never depend on API/file-list order.
- A segment may be referenced by more than one program.
- Original catalog records and file inventory remain intact.
- Unknown duration is null, not zero.
- Total duration is the sum of known segment durations and must be marked incomplete if any segment duration is unknown.
- The player treats a program as one queue: automatically advance on segment completion, retain one program-level progress position, and resume at the correct segment and offset.
- Access restrictions and source availability must be checked at playback time. Assembly does not grant playback or redistribution rights.

## Assembly states

- `suggested` — heuristic candidate; not approved for automatic playback as one program.
- `confirmed` — relationship reviewed or supported by strong deterministic evidence.
- `rejected` — reviewed and determined not to belong together.

Only `confirmed` programs should be presented as a seamless multi-segment title by default. Suggested groups may appear in an editorial review queue.

## Matching policy

Matching must be conservative and evidence-based. Useful evidence includes:

- Exact shared parent archive identifier.
- Explicit part/episode numbering and matching series/title.
- Filenames that clearly identify opening, feature, bonus, and closing roles.
- Matching collection or series metadata.
- Compatible title, release, and runtime context.

Weak evidence includes generic words such as "opening", "closing", "bonus", "cartoon", or "VHS" by themselves. Similar filenames or nearby archive identifiers alone are not sufficient.

Do not automatically join records solely because they share a broad franchise, year, character, uploader, or collection.

## Segment roles

Initial roles: `opening`, `main_program`, `intermission`, `bonus`, `bonus_cartoon`, `trailer`, `credits`, `closing`, and `unknown`.

Roles describe presentation order, not legal status or content suitability.

## Candidate generator (implemented)

`scripts/build_program_candidates.py` consumes enriched inventory JSONL and emits separate JSONL program manifests. It does not modify the source records or download media.

- Exact normalized-title matches can connect explicitly labeled opening/closing/bonus records with a plain-title long-form feature.
- Filename role clues can identify segments within an archive item.
- Candidate segments are sorted by semantic role order, not API response order.
- Every segment retains its exact archive identifier and filename.
- Candidates are always emitted as `suggested`; the generator never confirms a relationship automatically.
- Unknown segment durations make the total duration incomplete (null).
- Candidates include evidence, confidence, and a review warning.

The current inventory workflow runs the candidate generator against its balanced pilot sample and includes the candidate manifests in its artifact. The pilot is not a complete-catalog assembly pass.


## Standalone catalog builder (implemented)

`scripts/build_program_catalog.py` builds the library catalog from enriched inventory JSONL.

- Every distinct inventoried video file becomes a standalone, one-segment program entry with its exact Archive identifier and filename.
- This includes files that may also participate in a multi-segment suggestion. The standalone entry is retained; an assembled suggestion is additive, never a replacement.
- Multi-segment suggestions from `scripts/build_program_candidates.py` are included as separate `assembled_candidate` entries and remain review-only.
- Items with failed/missing inventory, no video candidates, Archive access restrictions, or private video files are recorded in a separate review queue.
- Unknown durations remain null and are flagged for review.
- The builder does not download media, modify source records, decide rights, or guarantee that a file is playable.

The inventory pilot now uploads:
- `vhsvault-program-catalog.jsonl` — standalone entries plus assembled suggestions.
- `vhsvault-program-catalog.summary.json` — entry counts and safety flags.
- `vhsvault-program-review-queue.jsonl` — source items requiring attention.

The current workflow is still a balanced **pilot sample** (up to 500 records), not yet a full 129,572-record inventory. A full-catalog inventory run is a separate scale-up step after validating the expanded pilot.

## Remaining implementation steps

1. Add a review/approval data store to promote suggestions to confirmed or rejected.
2. Validate manifests (unique segment IDs, explicit ordering, source references, duration arithmetic).
3. Run inventory and candidate generation at broader catalog scale.
4. Expose confirmed manifests to the future Android TV player as sequential queues.
