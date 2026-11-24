# VHS FEVER Catalog

Catalog discovery and metadata tools for **VHS FEVER**, a nostalgic Internet Archive movie-rental experience.

## First milestone: VHS Vault discovery

The first script queries the Internet Archive cursor-based Scraping API for items in the `vhsvault` collection. It writes metadata records to JSONL and a small summary report.

**It does not download video or audio files.** A matching collection entry is not, by itself, proof that a title is cleared for redistribution or playback. Rights and access checks will be handled as a separate catalog-enrichment step.

### Run locally

Requires Python 3.10+; the discovery script uses only the Python standard library.

```bash
python scripts/discover_vhsvault.py
```

By default, this fetches one page (up to 100 results) as a safe initial test:

- `data/vhsvault-discovery.jsonl`
- `data/vhsvault-discovery.summary.json`

Fetch more pages:

```bash
python scripts/discover_vhsvault.py --max-pages 5
```

Fetch until the API has no further cursor:

```bash
python scripts/discover_vhsvault.py --all
```

The API requires a page size of at least 100. The script validates that returned items actually list `vhsvault` in their collection metadata, removes duplicates, retries transient request failures, and pauses between pages. Review the summary and a sample of the JSONL before starting a full collection scan.

### Data policy

- Store descriptive metadata and file inventory information, not media payloads.
- Do not infer streaming or redistribution permission from public availability alone.
- Preserve license and access restriction metadata for later review.
- Keep generated catalog exports out of Git; use GitHub Actions artifacts or a separate data store for larger outputs.

## API references

- [Internet Archive Scraping API documentation](https://doc-tools.readthedocs.io/en/ia-test-gsod/item-search-apis.html)
- [Internet Archive Item Metadata API](https://doc-tools.readthedocs.io/en/ia-test-gsod/metadata.html)


## Metadata and VHS artwork enrichment

The file-inventory workflow now records image and PDF file metadata and ranks likely VHS front covers, back covers, spines, and wrap scans by filename. It also supports optional TMDB matching using an IMDb external ID first and a conservative title/year fallback. See [Metadata and Artwork Enrichment](docs/metadata-artwork.md).

TMDB lookups require the optional `TMDB_API_READ_ACCESS_TOKEN` Actions secret. Without it, the Archive artwork inventory still runs. TMDB's developer API is free for non-commercial use with attribution; commercial use requires separate licensing. All matches remain reviewable suggestions. No media payloads are downloaded.


## Artwork recognition quality gate

VHS artwork review now uses pre-inference frame filtering and a two-stage local CLIP decision: first identify whether an image is physical VHS packaging, then identify its visible component. Item title and original filename are included as context, while image-only scores are retained for comparison. A manually reviewed pilot #41 label set and evaluation script are included; see the Metadata and Artwork Enrichment guide. All predictions remain review-only, and scaling is blocked pending stronger manually verified evaluation.
