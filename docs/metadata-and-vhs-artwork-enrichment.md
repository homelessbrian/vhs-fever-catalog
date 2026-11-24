# VHS FEVER Metadata and Artwork Enrichment

## Product decision

TMDB is the primary title-identification, metadata, and cinematic-artwork provider. VHS FEVER should prefer authentic physical VHS packaging when it exists, while retaining TMDB poster, backdrop, and logo art as the fallback presentation layer.

The catalog must keep title identity, VHS release editions, and artwork assets separate. One film can have several VHS editions (different distributors, countries, years, or sleeve designs), and one title match must not collapse those editions into a single cover.

## Provider order

1. **TMDB** — primary match for movies and TV; primary source for canonical title metadata, TMDB IDs, IMDb external IDs when present, posters, backdrops, and logos.
2. **Video Tapes dot io** — first-choice specialist source for authentic VHS sleeves. Query by the matched TMDB movie ID. Preserve separate regional editions and their individual sleeve records.
3. **Internet Archive** — secondary research source for scans, item thumbnails, packaging, inserts, and other original material. Store provenance and access/rights notes; do not assume public availability grants reuse rights.
4. **IMDb fallback** — a fallback identity-resolution provider only where the project has a permitted, documented access method and the intended use is covered. Do not scrape IMDb pages or republish IMDb datasets by default. An IMDb ID may also be retained as a cross-reference when supplied by TMDB or another permitted source.
5. **Manual review** — unresolved or ambiguous records remain unmatched/reviewable; never attach artwork based only on a loose title similarity.

The specialist sleeve lookup depends on a TMDB ID. If TMDB cannot confidently identify a title, do not guess a TMDB ID from an uncertain IMDb match. Resolve the identity first, then query the sleeve archive.

## Matching workflow

For each VHS FEVER program:

1. Normalize the source title without overwriting the original Archive title.
2. Collect matching evidence: source title, alternate/original title, year/date, runtime, media type, description, creator/distributor, collection, and known identifiers.
3. Search TMDB using the strongest available title/year/type clues.
4. Score candidates using title similarity, year agreement, media-type agreement, and runtime agreement where available.
5. Auto-accept only high-confidence matches with no material contradictions. Keep medium/low-confidence candidates in a review queue.
6. Persist the TMDB ID and any returned external IDs. Do not use a poster/logo match as proof of title identity.
7. For a confirmed TMDB movie ID, query Video Tapes dot io for sleeve editions. Prefer the configured display region (initial default: US), but retain all returned editions and regions.
8. If no sleeve exists, use TMDB poster/backdrop/logo. If a sleeve exists, make it the signature artwork while retaining TMDB artwork as fallback.
9. If TMDB has no credible match, try the permitted IMDb fallback adapter. If identity is resolved, use the IMDb ID to attempt TMDB external-ID resolution before giving up on TMDB artwork. Never silently promote an ambiguous result.
10. Record provider, lookup date, evidence, confidence, and review state for every match.

### Suggested confidence policy

- **High**: exact or near-exact title plus compatible year and media type, with no conflicting evidence.
- **Medium**: plausible title with one missing or uncertain corroborating field.
- **Low**: title-only, conflicting year/type, compilation/episode ambiguity, or multiple plausible candidates.

Only high-confidence matches should be eligible for automatic acceptance. Thresholds should be calibrated against a hand-reviewed sample before a large enrichment run.

## Artwork model

Keep artwork as a collection of typed assets, not a single poster URL.

Suggested shape (illustrative):

```json
{
  "artwork": {
    "signature_asset_id": "sleeve-edition-123-spread",
    "assets": [
      {
        "asset_id": "sleeve-edition-123-spread",
        "provider": "videotapes_io",
        "edition_id": "123",
        "kind": "vhs_sleeve_spread",
        "region": "US",
        "country": "United States",
        "language": "en",
        "image_url": "https://provider-cdn.example/...",
        "width": 2400,
        "height": 1600,
        "folds": {
          "left": 0.32,
          "right": 0.67,
          "source": "measured"
        },
        "reuse_policy": "link_only",
        "attribution": "Video Tapes dot io; underlying artwork belongs to its rights holders"
      },
      {
        "asset_id": "sleeve-edition-123-front",
        "provider": "videotapes_io",
        "edition_id": "123",
        "kind": "vhs_front",
        "image_url": "https://provider-cdn.example/...",
        "reuse_policy": "link_only"
      },
      {
        "asset_id": "tmdb-poster-456",
        "provider": "tmdb",
        "kind": "poster",
        "image_url": "https://image.tmdb.org/...",
        "reuse_policy": "provider_terms"
      }
    ]
  }
}
```

The example URLs and IDs are placeholders, not real assets.

### Supported artwork kinds

- `vhs_sleeve_spread` — complete wrap, back/spine/front in reading order.
- `vhs_front` — front panel.
- `vhs_spine` — spine panel.
- `vhs_back` — back panel.
- `vhs_label` — cassette label or disc label where relevant.
- `tmdb_poster`
- `tmdb_backdrop`
- `tmdb_logo`
- `archive_cover` — Internet Archive cover/packaging image.
- `other`

Each asset should preserve its provider ID, original URL, dimensions, language, region/country, edition/release identity, attribution, retrieval time, and reuse policy where known. Keep the original spread as well as panel URLs when the source provides both. Do not fabricate a back or spine by stretching/cropping a front cover and label it as a genuine scan.

## Video Tapes dot io integration notes

The published API documentation describes:

- `GET /api/v1/films/{tmdbId}` for a film and its approved sleeves.
- `GET /api/v1/films/{tmdbId}/preferred?region=US` for a preferred regional sleeve.
- `POST /api/v1/preferred/lookup` for batch lookups by TMDB IDs.
- `GET /api/v1/sleeves/{id}` for sleeve metadata, spread URL, dimensions, and fold positions.
- `GET /api/v1/sleeves/{id}/image?part=spread|front|spine|back&w=` for the full wrap or an individual panel.

The API currently requires a read token. The published documentation says the read token permits 30 requests/minute and up to 500 TMDB IDs per batch lookup; handle HTTP 429 using `Retry-After`. Keep the token in GitHub Actions secrets or the runtime secret store, never in source control or catalog artifacts. If no token is configured, skip this provider cleanly and continue with TMDB artwork.

The sleeve archive's metadata is CC0-1.0, but the underlying artwork remains the property of its respective rights holders. Its published guidance asks users to link to artwork and not bulk-copy images. Therefore:
- Store catalogue metadata and source image URLs, not downloaded copies of its images.
- Render/link to the provider-hosted image URLs in accordance with its terms.
- Do not proxy, mirror, or bulk-cache the artwork unless the provider's terms or rights holder explicitly permit it.
- Respect a 410 response as a withdrawn sleeve and remove it from active selection.
- Preserve edition region/country and keep fan-made sleeves separate from official releases.

## TMDB and IMDb constraints

VHS FEVER is intended to become publicly available. Public availability alone does not necessarily make a project commercial; TMDB defines commercial use by whether the primary purpose is generating revenue for the owner. Until the project's business model is settled, implement against TMDB's developer API only for development and non-commercial use, include the required attribution and approved logo placement, and keep a provider adapter boundary so a commercial license can be substituted before any revenue-generating launch. Do not assume that a free developer API key grants commercial rights.

IMDb's public datasets are explicitly limited to personal, non-commercial use and cannot be repurposed to create a public movie-information database. IMDb also prohibits scraping absent express written consent. Therefore, the public-release architecture must not depend on IMDb data or scraping as its fallback. Keep an IMDb adapter interface dormant until written commercial/public-use permission or another clearly authorized access arrangement is obtained. In the meantime, use TMDB external IDs, Internet Archive source metadata, and manual review to resolve unmatched records without copying IMDb content.

Video Tapes dot io's catalogue metadata is CC0, but its underlying cover artwork remains owned by rights holders. Its published legal description characterizes the archive's artwork display as non-commercial preservation/research/public-interest use, and its data guidance asks other services to link to the images rather than bulk-copy them. For a public VHS FEVER release, treat the sleeve API as a discovery and provenance source only until the provider confirms that embedding/linking its hosted images in this specific public application is permitted. Do not download, proxy, mirror, cache, or rehost those scans without explicit permission. If permission is not available, show edition metadata and a link to the source page, and use artwork with a separately cleared license for the actual app display.

Internet Archive media and artwork also need item-level rights review. A public item, a Creative Commons label, and permission to redistribute are not interchangeable; preserve the supplied license and rights evidence and keep unknown/restricted items out of any public playback or artwork distribution path until reviewed.

## Data separation and review

Do not mix these entities:

- **Program** — the VHS FEVER viewing entry (may be a film, TV recording, cartoon, compilation, or multi-segment program).
- **External title match** — provider identity for a movie/TV title.
- **VHS edition** — a physical release for a particular market/distributor/version.
- **Artwork asset** — a specific image/panel belonging to an edition or metadata provider.

A VHS sleeve match should not automatically change the program's classification. A fan-made compilation, TV recording, or bonus feature may not be a film even if its title happens to match a TMDB movie.

Keep separate statuses for identity matching and artwork selection:
- `unmatched`, `candidate`, `confirmed`, `rejected` for title identity.
- `not_checked`, `found`, `not_found`, `withdrawn`, `needs_review` for sleeve availability/selection.

Every automatic decision should be reversible. Preserve original Archive metadata and the evidence used to make each enrichment decision.

## Implementation sequence

1. Merge and retain the existing program-manifest validation gate before enriching the full catalogue.
2. Add a versioned enrichment schema and validation tests.
3. Implement TMDB primary matching and metadata/artwork retrieval with rate-limit handling and cached lookup results.
4. Add the optional Video Tapes dot io adapter, token-based authentication, batch lookup, regional editions, panel/spread URLs, and withdrawn-artwork handling.
5. Add an IMDb fallback adapter only after the project's intended use and permitted access method are confirmed.
6. Run enrichment on a small, balanced sample; manually review false positives and sleeve coverage before scaling.
7. Export provenance and unresolved-match queues alongside the enriched catalog.

## References

- [TMDB API FAQ and attribution requirements](https://developer.themoviedb.org/docs/faq)
- [Video Tapes dot io API documentation](https://videotapes.io/docs)
- [Video Tapes dot io FAQ](https://videotapes.io/faq)
- [Video Tapes dot io data and artwork reuse guidance](https://videotapes.io/data)
- [Video Tapes dot io legal terms](https://videotapes.io/legal)
- [IMDb non-commercial dataset terms](https://developer.imdb.com/non-commercial-datasets/)


## TMDB title-matching adapter

The first TMDB implementation is deliberately read-only and review-first. `scripts/tmdb_enrichment.py` provides:

- Bearer-token authentication through the `TMDB_BEARER_TOKEN` environment variable (never a committed key or query-string credential).
- Movie and TV search through the TMDB v3 search endpoints. When the source media type is unknown, both are searched; when known, only that type is queried.
- Deterministic title normalization and candidate scoring using title similarity, release/first-air year, and media-type agreement.
- Evidence labels such as `exact_title`, `year_match`, `year_conflict`, and `media_type_conflict`.
- At most ten ranked candidates in the enrichment record's existing candidate shape.

This initial adapter never promotes a search result to a confirmed identity, even when confidence is high. High-confidence candidates remain reviewable until a separately designed approval/acceptance step exists. It does not fetch details, external IDs, or artwork yet, and it makes no network calls unless the client is explicitly invoked with a configured bearer token.

For a local smoke call after configuring a token:

```python
from scripts.tmdb_enrichment import TMDBClient, build_identity_match

client = TMDBClient()
results = client.search_candidates("The Thing", year=1982, media_type="movie")
match = build_identity_match(
    {"title": "The Thing", "year": 1982, "media_type": "movie"},
    results,
)
print(match)
```

TMDB v3 accepts the API Read Access Token as an `Authorization: Bearer` header. The token must be stored as a secret, not in source control. See the [official TMDB authentication documentation](https://developer.themoviedb.org/docs/authentication-application).


### TMDB metadata and fallback artwork preparation

The details stage uses the selected TMDB media type and ID to request title details with `append_to_response=external_ids,images`. Image language selection defaults to `en,null` so English-tagged and language-neutral artwork can both be considered. The image configuration endpoint is fetched once per client and supplies the secure base URL and supported image sizes; image URLs are assembled from that configuration rather than hard-coded CDN paths.

The normalized metadata sidecar keeps the TMDB ID, media type, title/original title, year, release or first-air date, runtime, overview, original language, genres, and permitted external IDs separate from the original Internet Archive record.

Poster, backdrop, and logo paths become individual artwork assets. The primary poster/backdrop paths are ordered first while matching image-list metadata is retained; duplicate paths are removed. The current builder selects `w500` posters, `w1280` backdrops, and original-size logos when those sizes are available, falling back to `original` where supported. It does not download images.

TMDB artwork assets are created with `reuse_policy: provider_terms`, attribution `TMDB`, and `display_status: pending_review`. They are not selected as signature artwork automatically. A separate release-readiness review must confirm attribution placement and applicable TMDB terms before changing their display status to approved.
