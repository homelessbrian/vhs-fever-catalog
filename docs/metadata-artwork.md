# VHS FEVER Metadata and Artwork Enrichment

## Separate movie metadata from edition packaging

The enrichment pipeline preserves three distinct things:

1. **Archive source** — the original Internet Archive item and exact filenames.
2. **Movie identity and artwork** — an optional TMDB match with TMDB poster, backdrop, and image endpoint references.
3. **Physical VHS edition artwork** — front cover, back cover, spine, wrap scan, or other packaging files found in the Archive item's file inventory.

A TMDB poster is never treated as a VHS cover. Packaging candidates retain their Archive identifier and exact filename.

## Archive artwork discovery

The file inventory records image files and PDF artwork documents by metadata only. Candidate ranking uses filename clues such as front, back, spine, wrap, insert, box, and sleeve. The result is a reviewable suggestion, not a visual confirmation. The pipeline does not download the image or PDF payload.

Candidate URLs point to the exact Archive item and filename. Private files are excluded from public artwork candidates. Access and rights must still be checked before display or playback.

## TMDB matching

Set the repository Actions secret TMDB_API_READ_ACCESS_TOKEN to enable TMDB lookups. Without it, the workflow still inventories packaging candidates and marks TMDB as skipped; no API key is embedded in source code.

Matching order:
1. Use a valid Internet Archive IMDb external ID with TMDB's find-by-external-ID endpoint.
2. If no IMDb ID is present, search by title and optional year.
3. Only a single normalized exact-title result is emitted as a medium-confidence suggestion. Ambiguous or missing results remain in review.
4. Every match has review_status: needs_review; no TMDB match silently merges source items or confirms a program identity.

TMDB image references are kept as movie-level poster, backdrop, and images endpoint fields. Logo candidates are available through the TMDB movie images endpoint and can be added after the match is reviewed.

## TMDB attribution and licensing

TMDB's current developer FAQ says API access is free for non-commercial use when attributed. It requires the notice:

> This product uses the TMDB API but is not endorsed or certified by TMDB.

The TMDB logo must identify API use in the app's About/Credits area and follow TMDB's logo rules. Commercial use requires a separate license from TMDB. The project must not assume that the free developer terms cover a public commercial service.

References:
- [TMDB FAQ](https://developer.themoviedb.org/docs/faq)
- [TMDB movie images endpoint](https://developer.themoviedb.org/reference/movie-images)
- [TMDB image URL basics](https://developer.themoviedb.org/docs/image-basics)
- [Internet Archive Metadata API](https://doc-tools.readthedocs.io/en/ia-test-gsod/metadata.html)

## Data and rights policy

- Metadata-only: no video, image, or PDF payloads are downloaded.
- Archive availability does not establish permission to redistribute or stream.
- TMDB artwork and Archive edition packaging remain separate.
- All inferred matches and packaging roles remain reviewable suggestions.
- Commercial deployment requires review of TMDB licensing and the rights for each Archive asset.
