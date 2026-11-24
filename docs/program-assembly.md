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

## Next implementation steps

1. Add a read-only candidate generator that consumes classified records and file inventories.
2. Emit suggested program manifests with evidence and confidence; do not alter source JSONL.
3. Add review/approval data to promote suggestions to confirmed or rejected.
4. Validate manifests (unique segment IDs, explicit ordering, source references, duration arithmetic).
5. Expose confirmed manifests to the future Android TV player as sequential queues.
