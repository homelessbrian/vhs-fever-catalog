#!/usr/bin/env python3
"""Validate versioned VHS FEVER enrichment sidecar records."""
from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

IDENTITY_STATUSES = {"unmatched", "candidate", "confirmed", "rejected"}
CONFIDENCE_LEVELS = {"high", "medium", "low", "none"}
MEDIA_TYPES = {"movie", "tv"}
SLEEVE_STATUSES = {"not_checked", "found", "not_found", "withdrawn", "needs_review"}
EDITION_PROVIDERS = {"videotapes_io", "internet_archive", "manual"}
ARTWORK_PROVIDERS = {"tmdb", "videotapes_io", "internet_archive", "manual"}
ARTWORK_KINDS = {
    "vhs_sleeve_spread", "vhs_front", "vhs_spine", "vhs_back", "vhs_label",
    "tmdb_poster", "tmdb_backdrop", "tmdb_logo", "archive_cover", "other",
}
REUSE_POLICIES = {
    "link_only", "provider_terms", "cc0", "cc_by", "cc_by_sa", "public_domain",
    "permission_granted", "permission_required", "unknown",
}
DISPLAY_STATUSES = {"approved", "link_only", "pending_review", "blocked"}
VHS_ARTWORK_KINDS = {
    "vhs_sleeve_spread", "vhs_front", "vhs_spine", "vhs_back", "vhs_label",
}


def _is_text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _is_member(value: Any, options: set[str]) -> bool:
    return isinstance(value, str) and value in options


def _is_integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_number(value: Any) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    return math.isfinite(float(value))


def _is_http_url(value: Any) -> bool:
    if not _is_text(value):
        return False
    try:
        parsed = urlsplit(value)
        return parsed.scheme in {"http", "https"} and bool(parsed.netloc)
    except ValueError:
        return False


def _is_datetime(value: Any) -> bool:
    if not _is_text(value):
        return False
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
        return True
    except ValueError:
        return False


def _validate_candidate(candidate: Any, prefix: str) -> list[str]:
    errors: list[str] = []
    if not isinstance(candidate, dict):
        return [f"{prefix} must be an object"]
    if candidate.get("provider") != "tmdb":
        errors.append(f"{prefix}.provider must be tmdb")
    if not _is_text(candidate.get("provider_id")):
        errors.append(f"{prefix}.provider_id must be a non-empty TMDB identifier")
    if not _is_member(candidate.get("media_type"), MEDIA_TYPES):
        errors.append(f"{prefix}.media_type must be one of {sorted(MEDIA_TYPES)}")
    if not _is_text(candidate.get("title")):
        errors.append(f"{prefix}.title must be a non-empty string")
    year = candidate.get("year")
    if year is not None and (not _is_integer(year) or not 1888 <= year <= 2200):
        errors.append(f"{prefix}.year must be null or an integer from 1888 through 2200")
    if not _is_member(candidate.get("confidence"), {"high", "medium", "low"}):
        errors.append(f"{prefix}.confidence must be high, medium, or low")
    evidence = candidate.get("evidence")
    if not isinstance(evidence, list) or any(not _is_text(item) for item in evidence):
        errors.append(f"{prefix}.evidence must be an array of non-empty strings")
    return errors


def validate_enrichment_record(record: Any) -> list[str]:
    """Return actionable validation errors for one enrichment record."""
    if not isinstance(record, dict):
        return ["record must be a JSON object"]

    errors: list[str] = []
    if not _is_integer(record.get("schema_version")) or record.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    if not _is_text(record.get("program_id")):
        errors.append("program_id must be a non-empty string")

    identity = record.get("identity_match")
    if not isinstance(identity, dict):
        errors.append("identity_match must be an object")
        identity = {}

    identity_status = identity.get("status")
    if not _is_member(identity_status, IDENTITY_STATUSES):
        errors.append(f"identity_match.status must be one of {sorted(IDENTITY_STATUSES)}")

    provider = identity.get("provider")
    if provider not in (None, "tmdb"):
        errors.append("identity_match.provider must be tmdb or null; IMDb is not a primary provider")
    provider_id = identity.get("provider_id")
    if provider_id is not None and not _is_text(provider_id):
        errors.append("identity_match.provider_id must be a non-empty string or null")

    media_type = identity.get("media_type")
    if media_type is not None and not _is_member(media_type, MEDIA_TYPES):
        errors.append(f"identity_match.media_type must be one of {sorted(MEDIA_TYPES)} or null")
    title = identity.get("title")
    if title is not None and not _is_text(title):
        errors.append("identity_match.title must be a non-empty string or null")
    year = identity.get("year")
    if year is not None and (not _is_integer(year) or not 1888 <= year <= 2200):
        errors.append("identity_match.year must be null or an integer from 1888 through 2200")
    confidence = identity.get("confidence")
    if not _is_member(confidence, CONFIDENCE_LEVELS):
        errors.append(f"identity_match.confidence must be one of {sorted(CONFIDENCE_LEVELS)}")

    evidence = identity.get("evidence")
    if not isinstance(evidence, list) or any(not _is_text(item) for item in evidence):
        errors.append("identity_match.evidence must be an array of non-empty strings")

    candidates = identity.get("candidates")
    if not isinstance(candidates, list):
        errors.append("identity_match.candidates must be an array")
        candidates = []
    else:
        for index, candidate in enumerate(candidates, 1):
            errors.extend(_validate_candidate(candidate, f"identity_match.candidates[{index}]"))
    if identity_status == "candidate" and not candidates:
        errors.append("candidate identity_match requires at least one candidate")
    if _is_member(identity_status, {"unmatched", "candidate", "rejected"}) and (provider is not None or provider_id is not None):
        errors.append(f"{identity_status} identity_match cannot contain a selected provider or provider_id")
    if identity_status == "confirmed":
        if provider != "tmdb":
            errors.append("confirmed identity_match requires provider tmdb")
        if not _is_text(provider_id):
            errors.append("confirmed identity_match requires a non-empty provider_id")
        if not _is_member(media_type, MEDIA_TYPES):
            errors.append("confirmed identity_match requires media_type movie or tv")
        if not _is_text(title):
            errors.append("confirmed identity_match requires a title")
        if not _is_member(confidence, {"high", "medium", "low"}):
            errors.append("confirmed identity_match requires a confidence level")

    external_ids = identity.get("external_ids")
    if not isinstance(external_ids, dict):
        errors.append("identity_match.external_ids must be an object")
    elif external_ids.get("imdb_id") is not None:
        imdb_id = external_ids["imdb_id"]
        if not isinstance(imdb_id, str) or not imdb_id.startswith("tt") or not imdb_id[2:].isdigit():
            errors.append("identity_match.external_ids.imdb_id must be null or an IMDb title ID")

    sleeve = record.get("vhs_sleeve_lookup")
    if not isinstance(sleeve, dict):
        errors.append("vhs_sleeve_lookup must be an object")
        sleeve = {}
    sleeve_status = sleeve.get("status")
    if not _is_member(sleeve_status, SLEEVE_STATUSES):
        errors.append(f"vhs_sleeve_lookup.status must be one of {sorted(SLEEVE_STATUSES)}")
    sleeve_provider = sleeve.get("provider")
    if not _is_member(sleeve_provider, EDITION_PROVIDERS):
        errors.append(f"vhs_sleeve_lookup.provider must be one of {sorted(EDITION_PROVIDERS)}")
    checked_at = sleeve.get("checked_at")
    if checked_at is not None and not _is_datetime(checked_at):
        errors.append("vhs_sleeve_lookup.checked_at must be an ISO 8601 datetime or null")
    if sleeve_status == "not_checked" and checked_at is not None:
        errors.append("not_checked sleeve lookup must have checked_at null")
    if sleeve_status not in (None, "not_checked") and not _is_text(checked_at):
        errors.append("checked sleeve lookup status requires checked_at")

    editions = sleeve.get("editions")
    edition_ids: set[str] = set()
    if not isinstance(editions, list):
        errors.append("vhs_sleeve_lookup.editions must be an array")
        editions = []
    else:
        for index, edition in enumerate(editions, 1):
            prefix = f"vhs_sleeve_lookup.editions[{index}]"
            if not isinstance(edition, dict):
                errors.append(f"{prefix} must be an object")
                continue
            edition_id = edition.get("edition_id")
            if not _is_text(edition_id):
                errors.append(f"{prefix}.edition_id must be a non-empty string")
            elif edition_id in edition_ids:
                errors.append(f"{prefix}.edition_id is duplicated: {edition_id}")
            else:
                edition_ids.add(edition_id)
            if not _is_member(edition.get("provider"), EDITION_PROVIDERS):
                errors.append(f"{prefix}.provider must be one of {sorted(EDITION_PROVIDERS)}")
            if not _is_http_url(edition.get("source_url")):
                errors.append(f"{prefix}.source_url must be an absolute HTTP(S) URL")
            if not _is_member(edition.get("authenticity"), {"official", "fan_made", "unknown"}):
                errors.append(f"{prefix}.authenticity must be official, fan_made, or unknown")
            release_year = edition.get("release_year")
            if release_year is not None and (
                not _is_integer(release_year) or not 1900 <= release_year <= 2200
            ):
                errors.append(f"{prefix}.release_year must be null or an integer from 1900 through 2200")
    if sleeve_status == "found" and not editions:
        errors.append("found sleeve lookup requires at least one edition")
    if sleeve_status == "not_found" and editions:
        errors.append("not_found sleeve lookup must not contain editions")

    artwork = record.get("artwork")
    if not isinstance(artwork, dict):
        errors.append("artwork must be an object")
        artwork = {}
    assets = artwork.get("assets")
    asset_ids: set[str] = set()
    assets_by_id: dict[str, dict[str, Any]] = {}
    if not isinstance(assets, list):
        errors.append("artwork.assets must be an array")
        assets = []
    else:
        for index, asset in enumerate(assets, 1):
            prefix = f"artwork.assets[{index}]"
            if not isinstance(asset, dict):
                errors.append(f"{prefix} must be an object")
                continue
            asset_id = asset.get("asset_id")
            if not _is_text(asset_id):
                errors.append(f"{prefix}.asset_id must be a non-empty string")
            elif asset_id in asset_ids:
                errors.append(f"{prefix}.asset_id is duplicated: {asset_id}")
            else:
                asset_ids.add(asset_id)
                assets_by_id[asset_id] = asset

            asset_provider = asset.get("provider")
            if not _is_member(asset_provider, ARTWORK_PROVIDERS):
                errors.append(f"{prefix}.provider must be one of {sorted(ARTWORK_PROVIDERS)}")
            if asset_provider == "tmdb" and not _is_text(asset.get("attribution")):
                errors.append(f"{prefix}.attribution is required for TMDB artwork")
            kind = asset.get("kind")
            if not _is_member(kind, ARTWORK_KINDS):
                errors.append(f"{prefix}.kind must be one of {sorted(ARTWORK_KINDS)}")
            if not _is_http_url(asset.get("source_url")):
                errors.append(f"{prefix}.source_url must be an absolute HTTP(S) URL")

            edition_id = asset.get("edition_id")
            if edition_id is not None and (not _is_text(edition_id) or edition_id not in edition_ids):
                errors.append(f"{prefix}.edition_id does not reference a known VHS edition")
            if _is_member(kind, VHS_ARTWORK_KINDS) and not _is_text(edition_id):
                errors.append(f"{prefix}.edition_id is required for VHS artwork")
            if _is_member(kind, VHS_ARTWORK_KINDS) and not _is_member(asset_provider, EDITION_PROVIDERS):
                errors.append(f"{prefix}.provider must identify the VHS artwork source")

            for dimension in ("width", "height"):
                value = asset.get(dimension)
                if value is not None and (not _is_integer(value) or value < 1):
                    errors.append(f"{prefix}.{dimension} must be a positive integer or null")

            reuse_policy = asset.get("reuse_policy")
            if not _is_member(reuse_policy, REUSE_POLICIES):
                errors.append(f"{prefix}.reuse_policy must be one of {sorted(REUSE_POLICIES)}")
            display_status = asset.get("display_status")
            if not _is_member(display_status, DISPLAY_STATUSES):
                errors.append(f"{prefix}.display_status must be one of {sorted(DISPLAY_STATUSES)}")
            if display_status == "approved" and _is_member(reuse_policy, {
                "link_only", "permission_required", "unknown"
            }):
                errors.append(f"{prefix}.display_status approved conflicts with reuse_policy {reuse_policy}")

            retrieved_at = asset.get("retrieved_at")
            if retrieved_at is not None and not _is_datetime(retrieved_at):
                errors.append(f"{prefix}.retrieved_at must be an ISO 8601 datetime or null")

            folds = asset.get("folds")
            if folds is not None:
                if not isinstance(folds, dict):
                    errors.append(f"{prefix}.folds must be an object or null")
                else:
                    left, right = folds.get("left"), folds.get("right")
                    if not _is_number(left) or not 0 <= left <= 1:
                        errors.append(f"{prefix}.folds.left must be a number between 0 and 1")
                    if not _is_number(right) or not 0 <= right <= 1:
                        errors.append(f"{prefix}.folds.right must be a number between 0 and 1")
                    if _is_number(left) and _is_number(right) and left >= right:
                        errors.append(f"{prefix}.folds.left must be less than folds.right")
                    if not _is_member(folds.get("source"), {"measured", "provider", "unknown"}):
                        errors.append(f"{prefix}.folds.source must be measured, provider, or unknown")

    signature_id = artwork.get("signature_asset_id")
    if signature_id is not None:
        if not _is_text(signature_id) or signature_id not in assets_by_id:
            errors.append("artwork.signature_asset_id must reference an existing asset")
        elif assets_by_id[signature_id].get("display_status") != "approved":
            errors.append("artwork.signature_asset_id must reference an asset approved for display")

    return errors


def validate_enrichment_records(records: list[Any]) -> list[str]:
    """Validate records and enforce one enrichment sidecar per program ID."""
    errors: list[str] = []
    seen_program_ids: set[str] = set()
    for index, record in enumerate(records, 1):
        for error in validate_enrichment_record(record):
            errors.append(f"record[{index}]: {error}")
        if isinstance(record, dict):
            program_id = record.get("program_id")
            if _is_text(program_id):
                if program_id in seen_program_ids:
                    errors.append(f"record[{index}]: duplicate program_id: {program_id}")
                seen_program_ids.add(program_id)
    return errors


def read_jsonl(path: Path) -> list[Any]:
    records = []
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSON on line {line_number}: {exc.msg}") from exc
    return records


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Enrichment JSONL to validate")
    parser.add_argument("--report", type=Path, help="Optional JSON validation report path")
    args = parser.parse_args()

    records = read_jsonl(args.input)
    errors = validate_enrichment_records(records)
    summary = {
        "records_checked": len(records),
        "valid": not errors,
        "error_count": len(errors),
        "errors": errors,
    }
    rendered = json.dumps(summary, indent=2)
    print(rendered)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")
    return 0 if not errors else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
