#!/usr/bin/env python3
"""Enrich inventoried VHS FEVER items with artwork candidates and optional TMDB matches.

This is metadata-only: it never downloads image or video payloads. Archive scans
identify likely edition artwork by filename; TMDB matches are suggestions and
remain reviewable. Set TMDB_API_READ_ACCESS_TOKEN to enable TMDB lookups.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".tif", ".tiff", ".webp"}
ROLE_PATTERNS = [
    ("vhs_front_cover", re.compile(r"\b(front|frontcover|case front|box front|sleeve front|jacket front)\b", re.I), 90),
    ("vhs_back_cover", re.compile(r"\b(back|rear|backcover|case back|box back|sleeve back|jacket back)\b", re.I), 90),
    ("vhs_spine", re.compile(r"\b(spine|side panel|sidepanel)\b", re.I), 95),
    ("vhs_full_wrap", re.compile(r"\b(wrap|full.?cover|full.?scan|case.?scan|box.?scan|insert)\b", re.I), 75),
    ("generic_packaging_art", re.compile(r"\b(vhs|video|tape|box|case|sleeve|jacket|label|artwork|cover|packaging)\b", re.I), 45),
]
IMDB_RE = re.compile(r"^tt\d{5,12}$")


def normalize_title(value: str) -> str:
    value = value.casefold()
    value = re.sub(r"\b(the|a|an)\b", " ", value)
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return " ".join(value.split())


NON_FILM_TITLE = re.compile(
    r"\b(home movie|home movies|home video|home videos|personal footage|"
    r"donated by|filmed by|video productions|archives?|tv archive|"
    r"tv spots?|commercials?|ad break|aircheck|recorded off|taped off|"
    r"capture|captured|hauppauge|compilation|all animated|all movies|"
    r"movie of the week intro|profile:)\b",
    re.I,
)


def tmdb_search_title(item: dict[str, Any]) -> tuple[str | None, str]:
    """Return a cleaned title or a reason to skip unreliable archive metadata."""
    raw = str(item.get("title") or "").strip()
    if not raw:
        return None, "missing_title"
    if NON_FILM_TITLE.search(raw):
        return None, "likely_archive_recording_or_compilation"
    title = raw
    title = re.sub(r"\s+(?:us|uk)\s+vhs\s+variants?\s*$", "", title, flags=re.I)
    title = re.sub(r"\s*\([^()]*\b(?:vhs|dvd|animated|variant|variants|capture|part \d+)[^()]*\)\s*$", "", title, flags=re.I)
    title = re.sub(r"\s*\[[^\]]*(?:vhs|dvd|variant|capture|part \d+)[^\]]*\]\s*$", "", title, flags=re.I)
    title = re.sub(r"\s*\((?:19|20)\d{2}\)\s*$", "", title)
    title = re.sub(r"\s*\[(?:19|20)\d{2}\]\s*$", "", title)
    title = re.sub(r"^\s*\d+[a-z]?\.?\s*", "", title, flags=re.I)
    title = re.sub(r"\s+", " ", title).strip(" -–—[]()")
    if len(normalize_title(title)) < 3:
        return None, "no_reliable_title_after_cleanup"
    return title, "cleaned_title"


def artwork_role(filename: str) -> tuple[str | None, int, str | None]:
    stem = Path(filename).stem.replace("_", " ").replace("-", " ")
    for role, pattern, score in ROLE_PATTERNS:
        match = pattern.search(stem)
        if match:
            return role, score, match.group(0)
    return None, 0, None


def build_artwork_candidates(item: dict[str, Any]) -> list[dict[str, Any]]:
    inv = item.get("vhs_fever_file_inventory") or {}
    identifier = str(item.get("identifier") or "").strip()
    candidates = []
    seen = set()
    files = list(inv.get("image_files") or []) + list(inv.get("artwork_document_files") or [])
    for file in files:
        name = str(file.get("name") or "").strip()
        if not name or name in seen or file.get("private"):
            continue
        seen.add(name)
        ext = Path(name).suffix.lower()
        if ext not in IMAGE_EXTS and ext != ".pdf":
            continue
        role, score, evidence = artwork_role(name)
        if not role:
            continue
        candidates.append({
            "archive_identifier": identifier,
            "file_name": name,
            "file_extension": ext,
            "candidate_role": role,
            "filename_score": score,
            "filename_evidence": evidence,
            "source_hint": file.get("source_hint"),
            "size_bytes": file.get("size_bytes"),
            "private": bool(file.get("private")),
            "review_status": "suggested",
            "asset_url": f"https://archive.org/download/{urllib.parse.quote(identifier, safe='')}/{urllib.parse.quote(name, safe='/')}",
            "note": "Filename-based role suggestion only; verify image content and edition before use.",
        })
    return sorted(candidates, key=lambda x: (-x["filename_score"], x["file_name"].casefold()))


def extract_imdb_id(item: dict[str, Any]) -> str | None:
    inv = item.get("vhs_fever_file_inventory") or {}
    external = inv.get("external_ids") or {}
    candidates = [external.get("imdb_id"), item.get("imdb"), item.get("imdb_id"), item.get("imdbid")]
    for value in candidates:
        if isinstance(value, str) and IMDB_RE.fullmatch(value.strip()):
            return value.strip()
    return None


def request_json(url: str, token: str | None = None, timeout: int = 25) -> dict[str, Any]:
    headers = {"Accept": "application/json", "User-Agent": "VHS-FEVER-Catalog/0.3"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        value = json.load(response)
    return value if isinstance(value, dict) else {}


def tmdb_image_url(path: str | None, size: str = "w500") -> str | None:
    if not path or not path.startswith("/"):
        return None
    return f"https://image.tmdb.org/t/p/{size}{path}"


def tmdb_payload(movie: dict[str, Any], match_method: str, confidence: str) -> dict[str, Any]:
    return {
        "tmdb_id": movie.get("id"),
        "imdb_id": movie.get("imdb_id"),
        "title": movie.get("title") or movie.get("original_title"),
        "release_date": movie.get("release_date"),
        "overview": movie.get("overview"),
        "match_method": match_method,
        "match_confidence": confidence,
        "review_status": "needs_review",
        "tmdb_url": f"https://www.themoviedb.org/movie/{movie['id']}" if movie.get("id") else None,
        "tmdb_poster": tmdb_image_url(movie.get("poster_path")),
        "tmdb_backdrop": tmdb_image_url(movie.get("backdrop_path"), "w1280"),
        "tmdb_logo": None,
        "images_endpoint": f"https://api.themoviedb.org/3/movie/{movie['id']}/images" if movie.get("id") else None,
        "note": "TMDB artwork is movie-level metadata, not VHS-edition packaging.",
    }


def find_tmdb_match(item: dict[str, Any], token: str) -> dict[str, Any]:
    imdb_id = extract_imdb_id(item)
    title = str(item.get("title") or "").strip()
    if imdb_id:
        url = "https://api.themoviedb.org/3/find/" + urllib.parse.quote(imdb_id) + "?external_source=imdb_id"
        result = request_json(url, token)
        movies = result.get("movie_results") or []
        if movies:
            return tmdb_payload(movies[0], "imdb_external_id", "high")
        return {"status": "not_found_by_imdb", "imdb_id": imdb_id, "review_status": "needs_review"}
    title, title_status = tmdb_search_title(item)
    if not title:
        return {"status": title_status, "review_status": "not_attempted",
                "original_title": str(item.get("title") or "")}
    year = item.get("year")
    if not year:
        date = str(item.get("date") or "")
        match = re.search(r"\b(18|19|20)\d{2}\b", date)
        year = match.group(0) if match else None
    query = urllib.parse.urlencode({"query": title, **({"year": str(year)[:4]} if year else {})})
    results = request_json("https://api.themoviedb.org/3/search/movie?" + query, token).get("results") or []
    normalized = normalize_title(title)
    exact = [m for m in results if normalize_title(str(m.get("title") or m.get("original_title") or "")) == normalized]
    if len(exact) == 1:
        movie = exact[0]
        return tmdb_payload(movie, "exact_title_search", "medium")
    return {
        "status": "ambiguous_title_match" if results else "not_found_by_title",
        "search_title": title,
        "search_year": year,
        "candidate_results": [
            {"tmdb_id": m.get("id"), "title": m.get("title"), "release_date": m.get("release_date"),
             "poster": tmdb_image_url(m.get("poster_path"))}
            for m in results[:5]
        ],
        "review_status": "needs_review",
    }


def enrich_record(item: dict[str, Any], token: str | None = None, do_tmdb: bool = True) -> dict[str, Any]:
    result = dict(item)
    enrichment = {
        "schema_version": 2,
        "archive_identifier": item.get("identifier"),
        "artwork_candidates": build_artwork_candidates(item),
        "tmdb": {"status": "skipped_no_api_token", "review_status": "needs_review"},
    }
    label = (item.get("vhs_fever_classification") or {}).get("label")
    eligible = label in {"movie_or_film", "cartoon_or_animation", "holiday_special"}
    if do_tmdb and token and eligible:
        try:
            enrichment["tmdb"] = find_tmdb_match(item, token)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError) as exc:
            enrichment["tmdb"] = {"status": "lookup_error", "error": str(exc)[:240], "review_status": "needs_review"}
    elif not eligible:
        enrichment["tmdb"] = {"status": "not_applicable_to_classification", "review_status": "not_required"}
    result["vhs_fever_metadata_artwork"] = enrichment
    return result


def read_jsonl(path: Path):
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--delay", type=float, default=0.25)
    parser.add_argument("--no-tmdb", action="store_true")
    args = parser.parse_args()
    token = os.environ.get("TMDB_API_READ_ACCESS_TOKEN")
    counts: dict[str, int] = {}
    total_artwork = 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as output:
        for item in read_jsonl(args.input):
            enriched = enrich_record(item, token, not args.no_tmdb)
            data = enriched["vhs_fever_metadata_artwork"]
            total_artwork += len(data["artwork_candidates"])
            status = data["tmdb"].get("status", "matched")
            if data["tmdb"].get("tmdb_id"):
                status = "matched_" + str(data["tmdb"].get("match_method"))
            counts[status] = counts.get(status, 0) + 1
            output.write(json.dumps(enriched, ensure_ascii=False, sort_keys=True) + "\n")
            if token and not args.no_tmdb:
                time.sleep(max(0, args.delay))
    summary = {
        "source_records": sum(counts.values()),
        "artwork_candidates": total_artwork,
        "tmdb_status_counts": counts,
        "tmdb_enabled": bool(token and not args.no_tmdb),
        "media_payloads_downloaded": False,
        "tmdb_attribution_required": bool(token),
        "tmdb_commercial_license_verified": False,
        "rights_determined": False,
        "note": "TMDB developer API is documented as free for non-commercial use with attribution; commercial use requires licensing. All matches and Archive artwork candidates remain reviewable suggestions.",
    }
    args.output.with_suffix(".summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
