# VHS FEVER Metadata and Artwork Enrichment

## Separate movie metadata from edition packaging

The enrichment pipeline preserves three distinct things:

1. **Archive source** — the original Internet Archive item and exact filenames.
2. **Movie identity and artwork** — an optional TMDB match with TMDB poster, backdrop, and image endpoint references.
3. **Physical VHS edition artwork** — front cover, back cover, spine, wrap scan, or other packaging files found in the Archive item's file inventory.

A TMDB poster is never treated as a VHS cover. Packaging candidates retain their Archive identifier and exact filename.

## Archive artwork discovery

The file inventory records image files and PDF artwork documents by metadata only. Candidate ranking uses filename clues such as front, back, spine, wrap, insert, box, and sleeve. The result is a reviewable suggestion, not a visual confirmation. Video-generated `.thumbs` directories and numbered frame images are excluded so sampled frames are not mislabeled as VHS packaging. The pipeline does not download the image or PDF payload.

Candidate URLs point to the exact Archive item and filename. Private files are excluded from public artwork candidates. Access and rights must still be checked before display or playback.

## TMDB matching

Set the repository Actions secret TMDB_API_READ_ACCESS_TOKEN to enable TMDB lookups. Without it, the workflow still inventories packaging candidates and marks TMDB as skipped; no API key is embedded in source code.

Matching order:
1. Search TMDB by the cleaned source title and optional year.
2. Rank results using normalized title similarity. A uniquely strong result (at least 0.88 similarity and a 0.04 lead over the next result) is emitted as a medium-confidence suggestion. Other results retain ranked candidates and similarity scores for review.
3. Preserve IMDb IDs only as passive cross-references when supplied by an authorized source; do not query TMDB using an Archive IMDb ID as the primary match path, scrape IMDb, or ingest IMDb datasets.
4. Every match remains reviewable; no TMDB match silently merges source items or confirms a program identity.

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

## Visual packaging review pilot

The inventory workflow now creates a contact sheet from Archive thumbnail previews:

- It selects up to 40 unique image assets, preferring the Archive `_thumb` preview rather than downloading the full-resolution original.
- It excludes private files, PDFs, video files, and thumbnail-directory/frame clutter.
- Each contact-sheet tile is labeled with the item title, filename, candidate role, and Archive identifier.
- The accompanying `vhs-artwork-review-manifest.json` preserves exact preview URLs, source filenames, license metadata, and preview fetch status.
- Preview downloads are capped at 2 MB per image and use the `archive.org` host only.

The contact sheet is a human-review aid, not an automated visual classification. A filename suggestion such as front/back/spine remains unverified until the image is inspected. The workflow does not download full-resolution artwork or any video payloads.


## Geometry-assisted packaging role suggestions

The contact-sheet builder now records the original preview dimensions and aspect ratio. Explicit filename roles remain highest priority. When filenames are generic, scan geometry adds a conservative candidate:
- Very narrow portrait scans suggest a spine candidate.
- Portrait cover-like scans suggest a cover-panel candidate; geometry cannot tell front from back.
- Wide landscape scans suggest a full-wrap candidate.
- Other shapes remain generic packaging art.

The manifest records the filename role, suggested role, basis, and confidence separately. Geometry-based suggestions are medium confidence and still require a person to inspect the image. This step does not alter source Archive metadata or automatically approve an asset for app display.


## Experimental local AI artwork recognition

The pilot includes a local, zero-shot CLIP review stage. It compares each small Archive preview with text descriptions of VHS front cover, back cover, spine, full wrap, cassette label, DVD packaging, movie poster, video screenshot, photograph, book or magazine cover, video-game packaging, and other or unclear imagery.

The model runs on the GitHub Actions CPU runner. It does not call a hosted inference API. The model is downloaded from Hugging Face at workflow runtime; no model weights or image payloads are committed to this repository. The workflow uses openai/clip-vit-base-patch32 through Transformers.

**Important limitations:** CLIP outputs relative similarity scores, not calibrated probabilities. The pipeline applies conservative thresholds, preserves the top candidates and score margin, and marks every result needs_review. It never auto-approves artwork, never changes the source Archive record, and never establishes the movie identity or edition. A VHS-looking image can still be a poster, a scan can contain multiple panels, and front-versus-back may require OCR and human inspection.

This is an experimental catalog-research stage only. The upstream model card says the model is intended as a research output and lists deployed use as out of scope; do not use its output as an automated production decision or as the sole basis for public display. Validate it against a manually labeled sample before considering another model or a production workflow.

Model references:
- [Xenova CLIP ONNX model card and MIT listing](https://huggingface.co/Xenova/clip-vit-base-patch32)
- [OpenAI CLIP model card and limitations](https://huggingface.co/openai/clip-vit-base-patch32)

The first pilot uses CPU inference and up to 40 small previews to keep resource use bounded. The manifest records model name, ranked labels, score margin, review status, and whether the top label is a VHS packaging candidate. No automatic approval is possible.

## Recognition precision work — two-stage review and baseline

The contact-sheet builder now excludes Archive thumbnail derivatives and likely video frames before AI inference. It checks .thumbs path components, source_hint=derivative, Archive thumbnail format, and _thumb names. For video items, explicit frame/still/screenshot clues in title or description exclude associated images; sequential frame-like filenames are also excluded when packaging context is absent. Numbered images in packaging-related items are retained but marked ambiguous. Each exclusion is recorded by Archive identifier, filename, and reason. Source inventory records are not changed.

The AI review is split into two decisions:

- Stage A — image type: VHS packaging, other physical media packaging, poster/promotional art, video frame/screenshot, photograph/other, or uncertain.
- Stage B — package component: front, back, spine, full wrap, cassette label, or insert/other. Stage B runs only when Stage A supports VHS packaging.

Both stages receive the Archive item title and original source filename. Image-only and contextual candidate scores are preserved separately; contextual scores drive the suggestion. Scores remain relative CLIP similarities, not calibrated probabilities. All results remain needs_review and no item is automatically approved.

A manually visually reviewed 10-image set from pilot #41 is stored in tests/fixtures/artwork_ground_truth_sample.jsonl. The legacy single-stage classifier baseline was precision 0.60 (3/5), recall 0.50 (3/6 decided positive cases), false-positive rate 1.00 (2/2 negative decisions), and coverage 0.80 (8/10). There were two false positives, both video frames from the Gonzalo Marcuzzi item; the third frame was an abstention. The three frame images are now removed by metadata filtering before AI inference. Seven Thomas & Friends package/cassette scans remain in the sample. These metrics are diagnostic only: the sample is small and deliberately mixed, not representative of the full Archive collection.

Run the evaluation against an AI manifest:

    python scripts/evaluate_artwork_ai.py --manifest data/artwork-review/vhs-artwork-ai-review-manifest.json --labels tests/fixtures/artwork_ground_truth_sample.jsonl --output data/artwork-review/ai-evaluation.json

The evaluator reports precision, recall, false-positive rate, coverage, abstentions, and component accuracy when two-stage component predictions exist. The inventory workflow runs this evaluation and uploads the report. Expand and independently verify the labeled set before considering larger samples. Do not scale AI across the full Archive collection or auto-approve artwork.
