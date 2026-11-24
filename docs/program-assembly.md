# VHS FEVER Program Assembly

## Purpose

A program is a logical viewing experience composed of one or more playable video segments. The original Internet Archive records remain unchanged. Program assembly adds relationships; it never merges, edits, or deletes source records.

## Catalog identity and playback model

1. **Title** — the logical movie or program shown once in the library, matched to TMDB when possible.
2. **Edition** — a distinct VHS release, distributor, cut, or packaging variant of that title.
3. **Source** — one actual Internet Archive upload/file that can be selected for playback. Alternate uploads and encodes remain selectable sources.
4. **Segment** — an ordered part of a source when one viewing copy is split across multiple files.
5. **Archive item** — the original Internet Archive identifier and metadata record, retained as provenance.

A title can have multiple editions; each edition can have multiple sources; each source can contain one or more ordered segments. A source can be attached to a matched title only when evidence supports that match.

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


## Standalone source catalog builder (implemented)

`scripts/build_program_catalog.py` builds the library catalog from enriched inventory JSONL.

- Every inventoried video file becomes a selectable source entry with its exact Archive identifier and filename.
- Alternate encodes and Archive derivatives are retained as sources; they are not silently discarded.
- Each source has a stable source_id. matched_title_id, edition_id, and alternate_source_group_id remain null until a later matching stage establishes those relationships.
- Matching is separate from source retention: duplicates should be grouped under a title as alternate sources, never deleted.
- This includes files that may also participate in a multi-segment suggestion. Source entries are retained; an assembled suggestion is additive, never a replacement.
- Multi-segment suggestions from `scripts/build_program_candidates.py` are included as separate `assembled_candidate` entries and remain review-only.
- Items with failed/missing inventory, no video candidates, Archive access restrictions, or private video files are recorded in a separate review queue.
- Unknown durations remain null and are flagged for review.
- The builder does not download media, modify source records, decide rights, or guarantee that a file is playable.

The inventory pilot now uploads:
- `vhsvault-program-catalog.jsonl` — standalone entries plus assembled suggestions.
- `vhsvault-program-catalog.summary.json` — entry counts and safety flags.
- `vhsvault-program-review-queue.jsonl` — source items requiring attention.

The current workflow is still a balanced pilot sample (120 records in the latest successful run), not a full Archive inventory. A full-catalog inventory run is a separate scale-up step after validating the matching and source-grouping behavior.

## Mandatory human approval for final catalog publication

TMDB and classification outputs are suggestions only. Automated matching must never promote a candidate into the final catalog.

The workflow has three distinct data layers:

1. **Source and discovery data** — raw Archive records, file inventories, source URLs, filenames, and durations. Preserve these regardless of match outcome.
2. **Review candidates** — proposed TMDB identities, confidence scores, evidence, candidate artwork, and automated classifications. These belong in review data and may be regenerated as matching improves.
3. **Final catalog** — only records with an explicit human-approved identity and human-approved content classification may be published as confirmed title entries.

A human reviewer must be able to:
- Confirm one proposed TMDB candidate.
- Search for and select a different TMDB candidate.
- Explicitly mark a record as having no suitable TMDB match.
- Confirm or correct the content classification independently of title identity.
- Leave a record pending without losing its source files or review history.

A confirmed identity must retain the reviewer decision, confirmation timestamp, selected provider and ID, and the evidence/candidate context used at review time. A rejection or unmatched decision must not be interpreted as permission to delete the source.

**Publication gate:** unreviewed, rejected, and unmatched records must never be represented as confirmed title identities in the final catalog JSON. They remain in the review queue/source data. The review website may display and play candidate sources, but that does not make them approved catalog entries.

Title identity, content classification, and VHS edition identification are separate review decisions. Confirming a TMDB movie does not confirm a particular VHS edition or its packaging. Alternate uploads and encodes remain separate selectable sources under the confirmed title when later source-grouping evidence supports that relationship.

## Remaining implementation steps

1. Add a review/approval data store to promote suggestions to confirmed or rejected.
2. Validate manifests (unique segment IDs, explicit ordering, source references, duration arithmetic).
3. Run inventory and candidate generation at broader catalog scale.
4. Expose confirmed manifests to the future Android TV player as sequential queues.


## Manifest validation (implemented)

`scripts/validate_program_manifests.py` validates generated program-catalog JSONL before it is used by later enrichment or playback stages.

It checks required program/source identifiers, unique segment IDs and source references, contiguous explicit segment ordering, playback-mode constraints, finite non-negative durations, duration totals/completeness, and duplicate program IDs across the catalog. Assembled candidates must remain suggested and review-required. The command exits non-zero when any validation error is found and can optionally write a JSON report:

```bash
python scripts/validate_program_manifests.py --input data/vhsvault-program-catalog.jsonl --report data/vhsvault-program-catalog.validation.json
```

Validation checks data integrity only; it does not establish title identity, playback availability, or rights.


## Title / edition / alternate-source matching policy

- Preserve every playable source file, including Archive streaming derivatives and alternate encodes.
- Match likely duplicate uploads to one logical title only when title evidence is sufficient; retain each upload as a separately selectable source.
- Keep different VHS releases, distributors, cuts, and packaging as distinct editions under the same title when evidence supports that distinction.
- Keep multipart files as ordered segments of one source, not as unrelated duplicate titles.
- Do not equate every numbered file with a separate movie or assume numbered files are multipart without supporting title, sequence, and runtime evidence.
- Never delete source records during matching. A source may be marked unavailable or restricted, but its provenance remains in the catalog.
- When matching is uncertain, leave sources ungrouped and route them for review rather than making a destructive merge.
