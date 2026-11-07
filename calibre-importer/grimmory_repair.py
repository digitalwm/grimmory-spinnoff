import json
import os
import re
import subprocess
import time
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple
from urllib import error, parse, request
from html.parser import HTMLParser
from pathlib import Path


GRIMMORY_BASE_URL = os.environ.get("GRIMMORY_BASE_URL", "http://grimmory:6060").rstrip("/")
GRIMMORY_USERNAME = os.environ.get("GRIMMORY_USERNAME", "").strip()
GRIMMORY_PASSWORD = os.environ.get("GRIMMORY_PASSWORD", "").strip()
GRIMMORY_LOW_SCORE_THRESHOLD = float(os.environ.get("GRIMMORY_LOW_SCORE_THRESHOLD", "30") or "30")
GRIMMORY_PAGE_SIZE = int(os.environ.get("GRIMMORY_PAGE_SIZE", "200") or "200")
GRIMMORY_HTTP_RETRY_ATTEMPTS = max(1, int(os.environ.get("GRIMMORY_HTTP_RETRY_ATTEMPTS", "3") or "3"))
GRIMMORY_HTTP_RETRY_DELAY_SECONDS = max(0.0, float(os.environ.get("GRIMMORY_HTTP_RETRY_DELAY_SECONDS", "2") or "2"))
GRIMMORY_UPDATE_TIMEOUT_SECONDS = int(os.environ.get("GRIMMORY_UPDATE_TIMEOUT_SECONDS", "60") or "60")
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "").rstrip("/")
LLM_API_KEY = os.environ.get("LLM_API_KEY", "").strip()
LLM_MODEL = os.environ.get("LLM_MODEL", "").strip()
LLM_TIMEOUT_SECONDS = int(os.environ.get("LLM_TIMEOUT_SECONDS", "60") or "60")
LLM_RETRY_ATTEMPTS = max(1, int(os.environ.get("LLM_RETRY_ATTEMPTS", "3") or "3"))
BOOKS_ROOT = Path(os.environ.get("GRIMMORY_BOOKS_ROOT", "/books")).resolve()
BOOK_SAMPLE_PAGES = max(1, int(os.environ.get("LLM_BOOK_SAMPLE_PAGES", "2") or "2"))
BOOK_SAMPLE_MAX_CHARS = 12000
BOOK_SAMPLE_RETRY_MAX_CHARS = 30000
BOOK_SAMPLE_RETRY_PAGES = 5
REVIEW_QUEUE_PATH = Path(os.environ.get("REVIEW_QUEUE_PATH", "/data/llm-review-queue.json"))

DIRECT_RE = re.compile(r"^(?P<author>.+?)\s+-\s+(?P<title>.+)$")
SERIES_RE = re.compile(r"^(?P<author>.+?)\s+-\s+\[(?P<series>.+?)\]\s+-\s+(?P<title>.+)$")
FILE_URL_RE = re.compile(r"^file:/+", re.IGNORECASE)
UNKNOWN_AUTHOR_RE = re.compile(r"^\s*(unknown|various|anonymous)\s*$", re.IGNORECASE)
TRAILING_COPY_RE = re.compile(r"__\d+$")


@dataclass
class Suggestion:
    title: str
    authors: List[str]
    subtitle: Optional[str] = None
    series_name: Optional[str] = None
    series_number: Optional[float] = None
    confidence: float = 0.0
    rationale: str = ""
    source_text: str = ""
    needs_llm: bool = False

    def to_dict(self) -> Dict[str, object]:
        return {
            "title": self.title,
            "authors": self.authors,
            "subtitle": self.subtitle,
            "seriesName": self.series_name,
            "seriesNumber": self.series_number,
            "confidence": self.confidence,
            "rationale": self.rationale,
            "sourceText": self.source_text,
            "needsLlm": self.needs_llm,
        }


def config_status() -> Dict[str, object]:
    return {
        "grimmoryBaseUrl": GRIMMORY_BASE_URL,
        "hasGrimmoryCredentials": bool(GRIMMORY_USERNAME and GRIMMORY_PASSWORD),
        "defaultLowScoreThreshold": GRIMMORY_LOW_SCORE_THRESHOLD,
        "grimmoryHttpRetryAttempts": GRIMMORY_HTTP_RETRY_ATTEMPTS,
        "grimmoryUpdateTimeoutSeconds": GRIMMORY_UPDATE_TIMEOUT_SECONDS,
        "hasLlmConfig": bool(LLM_BASE_URL and LLM_API_KEY and LLM_MODEL),
        "llmBaseUrl": LLM_BASE_URL,
        "llmModel": LLM_MODEL,
        "llmRetryAttempts": LLM_RETRY_ATTEMPTS,
    }


def _http_json(
    method: str,
    url: str,
    *,
    token: Optional[str] = None,
    body: Optional[Dict[str, object]] = None,
    timeout: int = 30,
) -> object:
    headers = {"Accept": "application/json"}
    payload = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = request.Request(url, method=method, headers=headers, data=payload)
    last_exc: Optional[BaseException] = None
    for attempt in range(1, GRIMMORY_HTTP_RETRY_ATTEMPTS + 1):
        try:
            with request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
                content_type = resp.headers.get("Content-Type", "")
            break
        except error.HTTPError as exc:
            last_exc = exc
            if exc.code not in {429, 500, 502, 503, 504} or attempt >= GRIMMORY_HTTP_RETRY_ATTEMPTS:
                raise
        except (TimeoutError, error.URLError) as exc:
            last_exc = exc
            if attempt >= GRIMMORY_HTTP_RETRY_ATTEMPTS:
                raise
        if GRIMMORY_HTTP_RETRY_DELAY_SECONDS:
            time.sleep(GRIMMORY_HTTP_RETRY_DELAY_SECONDS * attempt)
    else:
        raise RuntimeError(str(last_exc) if last_exc else "HTTP request failed")
    if not raw:
        return None
    if "json" in content_type:
        return json.loads(raw.decode("utf-8"))
    return raw.decode("utf-8", errors="replace")


def _require_grimmory_credentials() -> Optional[Dict[str, object]]:
    if GRIMMORY_USERNAME and GRIMMORY_PASSWORD:
        return None
    return {
        "ok": False,
        "message": "Set GRIMMORY_USERNAME and GRIMMORY_PASSWORD in the stack .env before using Grimmory repair.",
    }


def _login() -> str:
    payload = {"username": GRIMMORY_USERNAME, "password": GRIMMORY_PASSWORD}
    data = _http_json("POST", f"{GRIMMORY_BASE_URL}/api/v1/auth/login", body=payload, timeout=20)
    if not isinstance(data, dict) or "accessToken" not in data:
        raise RuntimeError("Grimmory login did not return an access token")
    return str(data["accessToken"])


def _iter_app_books(token: str):
    page = 0
    while True:
        query = parse.urlencode(
            [
                ("page", page),
                ("size", GRIMMORY_PAGE_SIZE),
                ("sort", "metadataMatchScore"),
                ("dir", "asc"),
            ]
        )
        data = _http_json(
            "GET",
            f"{GRIMMORY_BASE_URL}/api/v1/app/books?{query}",
            token=token,
            timeout=30,
        )
        if not isinstance(data, dict):
            break
        page_items = data.get("content") or []
        if not isinstance(page_items, list):
            break
        total = int(data.get("totalElements") or (page * GRIMMORY_PAGE_SIZE + len(page_items)))
        for item in page_items:
            if isinstance(item, dict):
                yield item, total
        if not data.get("hasNext"):
            break
        page += 1


def _fetch_book_detail(token: str, book_id: int) -> Dict[str, object]:
    data = _http_json(
        "GET",
        f"{GRIMMORY_BASE_URL}/api/v1/books/{book_id}",
        token=token,
        timeout=20,
    )
    return data if isinstance(data, dict) else {}


def _clean_text(value: Optional[str]) -> str:
    if not value:
        return ""
    value = value.strip()
    value = value.removesuffix(".txt")
    value = value.removesuffix(".htm")
    value = value.removesuffix(".html")
    value = value.removesuffix(".pdf")
    value = value.removesuffix(".epub")
    value = value.removesuffix(".mobi")
    value = TRAILING_COPY_RE.sub("", value)
    return value.strip()


def _decode_source_text(value: Optional[str]) -> str:
    raw = value or ""
    if not raw:
        return ""
    decoded = parse.unquote(raw)
    if FILE_URL_RE.match(decoded):
        decoded = FILE_URL_RE.sub("", decoded)
    decoded = decoded.replace("\\", "/")
    decoded = decoded.rsplit("/", 1)[-1]
    decoded = decoded.replace("|", ":")
    return _clean_text(decoded)


def _author_list(author_text: str) -> List[str]:
    items = re.split(r"\s*;\s*|\s*&\s*|\s+and\s+", author_text)
    authors = []
    for item in items:
        item = _clean_text(item)
        if not item or UNKNOWN_AUTHOR_RE.match(item):
            continue
        if "," in item and not item.endswith(", Jr") and not item.endswith(", Sr"):
            bits = [part.strip() for part in item.split(",", 1)]
            if len(bits) == 2 and bits[0] and bits[1]:
                item = f"{bits[1]} {bits[0]}".strip()
        authors.append(item)
    deduped = []
    seen = set()
    for author in authors:
        key = author.casefold()
        if key not in seen:
            seen.add(key)
            deduped.append(author)
    return deduped


def _extract_series_parts(series_text: str) -> Tuple[str, Optional[float]]:
    series_text = _clean_text(series_text)
    match = re.match(r"^(?P<name>.+?)\s+(?P<number>\d+(?:\.\d+)?)$", series_text)
    if not match:
        return series_text, None
    number_raw = match.group("number")
    number = float(number_raw)
    if number.is_integer():
        number = int(number)
    return match.group("name").strip(), number


def _suggest_from_text(source_text: str) -> Optional[Suggestion]:
    cleaned = _clean_text(source_text)
    if not cleaned:
        return None

    series_match = SERIES_RE.match(cleaned)
    if series_match:
        authors = _author_list(series_match.group("author"))
        if authors:
            series_name, series_number = _extract_series_parts(series_match.group("series"))
            return Suggestion(
                title=_clean_text(series_match.group("title")),
                authors=authors,
                series_name=series_name or None,
                series_number=series_number,
                confidence=0.98,
                rationale="Parsed from author-series-title pattern.",
                source_text=source_text,
            )

    pieces = [piece.strip() for piece in cleaned.split(" - ") if piece.strip()]
    if len(pieces) == 3:
        authors = _author_list(pieces[0])
        if authors:
            return Suggestion(
                title=_clean_text(pieces[1]),
                subtitle=_clean_text(pieces[2]),
                authors=authors,
                confidence=0.93,
                rationale="Parsed from author-title-subtitle pattern.",
                source_text=source_text,
                needs_llm=True,
            )

    direct_match = DIRECT_RE.match(cleaned)
    if direct_match:
        authors = _author_list(direct_match.group("author"))
        if authors:
            return Suggestion(
                title=_clean_text(direct_match.group("title")),
                authors=authors,
                confidence=0.96,
                rationale="Parsed from author-title pattern.",
                source_text=source_text,
            )

    return None


def _candidate_reason(summary: Dict[str, object], detail: Dict[str, object], threshold: float) -> List[str]:
    reasons = []
    score = summary.get("metadataMatchScore")
    authors = summary.get("authors") or []
    title = str(summary.get("title") or "")
    primary_file = detail.get("primaryFile") or {}
    file_name = str(primary_file.get("fileName") or "")

    if score is not None and float(score) < threshold:
        reasons.append(f"low_score<{threshold:g}")
    if not authors:
        reasons.append("missing_authors")
    if title.startswith("file:///") or file_name.startswith("file:///"):
        reasons.append("file_url_title")
    if " - " in title and not authors:
        reasons.append("title_contains_filename_pattern")
    return reasons


def _load_exception_book_ids() -> set[int]:
    try:
        queue = json.loads(REVIEW_QUEUE_PATH.read_text(encoding="utf-8"))
        if not isinstance(queue, dict):
            return set()
        return {
            int(book_id)
            for book_id, item in queue.items()
            if isinstance(item, dict) and item.get("exception") is True
        }
    except (OSError, json.JSONDecodeError, TypeError, ValueError):
        return set()


def _pick_source_text(summary: Dict[str, object], detail: Dict[str, object]) -> str:
    title = str(summary.get("title") or "")
    metadata = detail.get("metadata") or {}
    primary_file = detail.get("primaryFile") or {}
    for value in (
        title,
        str(primary_file.get("fileName") or ""),
        str(primary_file.get("fileSubPath") or ""),
        str(metadata.get("title") or ""),
    ):
        decoded = _decode_source_text(value)
        if decoded:
            return decoded
    return _clean_text(title)


class _TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style", "head"}:
            self.hidden += 1
        elif tag in {"p", "div", "br", "li", "h1", "h2", "h3"}:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in {"script", "style", "head"} and self.hidden:
            self.hidden -= 1
        elif tag in {"p", "div", "li"}:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def _text_from_html(raw: bytes) -> str:
    parser = _TextExtractor()
    parser.feed(raw.decode("utf-8", errors="replace"))
    return re.sub(r"\s+", " ", " ".join(parser.parts)).strip()


def _sample_book_text(
    primary_file: Dict[str, object],
    *,
    page_count: int = BOOK_SAMPLE_PAGES,
    max_chars: int = BOOK_SAMPLE_MAX_CHARS,
) -> Tuple[str, Optional[str]]:
    """Read a bounded opening sample from a supported EPUB/PDF."""
    file_path = str(primary_file.get("filePath") or "")
    if not file_path:
        return "", "Grimmory did not provide a file path"
    path = Path(file_path)
    if not path.is_absolute():
        path = BOOKS_ROOT / path
    try:
        path = path.resolve(strict=True)
        if os.path.commonpath((str(BOOKS_ROOT), str(path))) != str(BOOKS_ROOT) or not path.is_file():
            return "", "Book path is outside the read-only library mount"
    except (OSError, ValueError):
        return "", "Book file is not available in the importer container"
    suffix = path.suffix.lower()
    try:
        if suffix == ".pdf":
            result = subprocess.run(
                ["pdftotext", "-f", "1", "-l", str(page_count), "-layout", str(path), "-"],
                capture_output=True, text=True, timeout=20, check=False,
            )
            if result.returncode:
                return "", "Could not extract text from the first PDF pages"
            sample = _clean_text(result.stdout)[:max_chars]
            return (sample, None) if sample else ("", "The opening PDF pages contained no extractable text")
        if suffix == ".epub":
            with zipfile.ZipFile(path) as archive:
                container = ET.fromstring(archive.read("META-INF/container.xml"))
                rootfile = next(node.attrib["full-path"] for node in container.iter() if node.tag.endswith("rootfile"))
                opf = ET.fromstring(archive.read(rootfile))
                manifest = {node.attrib.get("id"): node.attrib.get("href") for node in opf.iter() if node.tag.endswith("item")}
                spine = [node.attrib.get("idref") for node in opf.iter() if node.tag.endswith("itemref")]
                base = str(Path(rootfile).parent)
                chunks = []
                for item_id in spine:
                    href = manifest.get(item_id)
                    if not href:
                        continue
                    member = str(Path(base, parse.unquote(href)))
                    raw = archive.read(member)
                    text = _text_from_html(raw)
                    if text:
                        chunks.append(text)
                    if len(" ".join(chunks)) >= max_chars:
                        break
                sample = _clean_text(" ".join(chunks))[:max_chars]
                return (sample, None) if sample else ("", "The opening EPUB section contained no extractable text")
    except (OSError, KeyError, StopIteration, ET.ParseError, zipfile.BadZipFile, RuntimeError):
        return "", f"Could not extract a text sample from {suffix or 'this format'}"
    return "", f"Text sampling is not supported for {suffix or 'this format'}"


def _build_update_payload(book_id: int, proposed: Dict[str, object]) -> Dict[str, object]:
    metadata = {
        "bookId": book_id,
        "title": proposed.get("title"),
        "authors": proposed.get("authors") or [],
    }
    if proposed.get("subtitle") is not None:
        metadata["subtitle"] = proposed.get("subtitle")
    if proposed.get("seriesName") is not None:
        metadata["seriesName"] = proposed.get("seriesName")
    if proposed.get("seriesNumber") is not None:
        metadata["seriesNumber"] = proposed.get("seriesNumber")
    return {
        "metadata": metadata,
        "clearFlags": {
            "title": False,
            "subtitle": False,
            "authors": False,
            "seriesName": False,
            "seriesNumber": False,
        },
    }


def scan_library(
    threshold: Optional[float] = None,
    max_candidates: Optional[int] = None,
    progress_cb: Optional[Callable[[str, Dict[str, object]], None]] = None,
) -> Dict[str, object]:
    config_error = _require_grimmory_credentials()
    if config_error:
        return config_error

    threshold = float(threshold if threshold is not None else GRIMMORY_LOW_SCORE_THRESHOLD)
    if progress_cb:
        progress_cb(
            "scan",
            {
                "state": "scanning",
                "phase": "login",
                "message": "Logging in to Grimmory",
                "processed": 0,
                "total": 0,
                "candidateCount": 0,
            },
        )
    token = _login()
    exception_ids = _load_exception_book_ids()
    candidates = []
    low_score_count = 0
    low_score_missing_authors = 0
    scanned_count = 0
    ignored_exception_count = 0
    total_available = 0
    limit_reached = False

    for summary, total_available in _iter_app_books(token):
        scanned_count += 1
        book_id = int(summary.get("id"))
        if book_id in exception_ids:
            ignored_exception_count += 1
            if progress_cb and (scanned_count == 1 or scanned_count % 25 == 0):
                progress_cb(
                    "scan",
                    {
                        "state": "scanning",
                        "phase": "scanning",
                        "message": f"Scanned {scanned_count}/{total_available} books; ignored {ignored_exception_count} exception(s)",
                        "processed": scanned_count,
                        "total": total_available,
                        "candidateCount": len(candidates),
                    },
                )
            continue
        score = summary.get("metadataMatchScore")
        if score is not None and float(score) < threshold:
            low_score_count += 1
            if not (summary.get("authors") or []):
                low_score_missing_authors += 1

        if score is None and (summary.get("authors") or []):
            if progress_cb and (scanned_count == 1 or scanned_count % 25 == 0):
                progress_cb(
                    "scan",
                    {
                        "state": "scanning",
                        "phase": "scanning",
                        "message": f"Scanned {scanned_count}/{total_available} books",
                        "processed": scanned_count,
                        "total": total_available,
                        "candidateCount": len(candidates),
                    },
                )
            continue

        if progress_cb:
            progress_cb(
                "scan",
                {
                    "state": "scanning",
                "phase": "scanning",
                "message": f"Checking book {scanned_count}/{total_available}",
                "processed": scanned_count,
                "total": total_available,
                    "candidateCount": len(candidates),
                    "id": book_id,
                    "title": summary.get("title"),
                },
            )
        detail = _fetch_book_detail(token, book_id)
        reasons = _candidate_reason(summary, detail, threshold)
        if not reasons:
            continue

        source_text = _pick_source_text(summary, detail)
        suggestion = _suggest_from_text(source_text)
        if suggestion is None and source_text != str(summary.get("title") or ""):
            suggestion = _suggest_from_text(str(summary.get("title") or ""))

        candidates.append(
            {
                "id": book_id,
                "score": score,
                "title": summary.get("title"),
                "authors": summary.get("authors") or [],
                "seriesName": summary.get("seriesName"),
                "fileName": (detail.get("primaryFile") or {}).get("fileName"),
                "filePath": (detail.get("primaryFile") or {}).get("filePath"),
                "bookType": (detail.get("primaryFile") or {}).get("bookType"),
                "reasons": reasons,
                "sourceText": source_text,
                "suggestion": suggestion.to_dict() if suggestion else None,
                "canAutoFix": bool(suggestion and not suggestion.needs_llm and suggestion.confidence >= 0.95),
            }
        )
        if progress_cb:
            progress_cb(
                "scan",
                {
                    "state": "scanning",
                    "phase": "scanning",
                    "message": f"Found {len(candidates)} repair candidate(s)",
                    "processed": scanned_count,
                    "total": total_available,
                    "candidateCount": len(candidates),
                    "id": book_id,
                    "title": summary.get("title"),
                },
            )
        if max_candidates is not None and max_candidates > 0 and len(candidates) >= max_candidates:
            limit_reached = True
            break

    if progress_cb and limit_reached:
        progress_cb(
            "scan",
            {
                "state": "scanning",
                "phase": "limit_reached",
                "message": f"Reached the limit of {max_candidates} repair candidates after scanning {scanned_count} books",
                "processed": scanned_count,
                "total": total_available,
                "candidateCount": len(candidates),
            },
        )

    return {
        "ok": True,
        "threshold": threshold,
        "scoreScaleBug": {
            "matchScoreApiBucketFilterLikelyBroken": True,
            "detail": "Live metadataMatchScore values appear to be on a 0-100 scale, while Grimmory's app matchScore bucket filter compares against 0.00-0.95.",
        },
        "counts": {
            "totalBooks": scanned_count,
            "totalAvailable": total_available,
            "lowScoreBooks": low_score_count,
            "lowScoreMissingAuthors": low_score_missing_authors,
            "candidates": len(candidates),
            "ignoredExceptions": ignored_exception_count,
            "autoFixable": sum(1 for item in candidates if item["canAutoFix"]),
            "limitReached": limit_reached,
            "candidateLimit": max_candidates,
        },
        "candidates": candidates,
        "config": config_status(),
    }


def update_book(book_id: int, proposed: Dict[str, object]) -> Dict[str, object]:
    config_error = _require_grimmory_credentials()
    if config_error:
        return config_error

    token = _login()
    payload = _build_update_payload(book_id, proposed)
    result = _http_json(
        "PUT",
        f"{GRIMMORY_BASE_URL}/api/v1/books/{book_id}/metadata?mergeCategories=false&replaceMode=REPLACE_ALL",
        token=token,
        body=payload,
        timeout=GRIMMORY_UPDATE_TIMEOUT_SECONDS,
    )
    if not isinstance(result, dict):
        return {"ok": False, "message": "Unexpected Grimmory response while updating metadata."}
    return {"ok": True, "book": result}


def delete_books(book_ids: List[int]) -> Dict[str, object]:
    config_error = _require_grimmory_credentials()
    if config_error:
        return config_error
    ids = sorted({int(book_id) for book_id in book_ids})
    if not ids:
        return {"ok": False, "message": "Select at least one book to delete."}
    token = _login()
    query = parse.urlencode({"ids": ",".join(str(book_id) for book_id in ids)})
    result = _http_json(
        "DELETE",
        f"{GRIMMORY_BASE_URL}/api/v1/books?{query}",
        token=token,
        timeout=GRIMMORY_UPDATE_TIMEOUT_SECONDS,
    )
    if not isinstance(result, dict):
        return {"ok": False, "message": "Unexpected Grimmory response while deleting books."}
    deleted = sorted({int(book_id) for book_id in result.get("deleted", [])})
    failed_files = sorted({int(book_id) for book_id in result.get("failedFileDeletions", [])})
    return {
        "ok": set(ids) == set(deleted) and not failed_files,
        "deleted": deleted,
        "failedFileDeletions": failed_files,
        "message": "Deleted from Grimmory and disk." if not failed_files else "Grimmory removed the records, but some files could not be deleted from disk.",
    }


def refresh_fixed_books(book_ids: List[int]) -> Dict[str, object]:
    unique_ids = sorted({int(book_id) for book_id in book_ids if book_id is not None})
    if not unique_ids:
        return {"ok": True, "message": "No updated books to refresh.", "bookIds": []}

    token = _login()
    result = _http_json(
        "POST",
        f"{GRIMMORY_BASE_URL}/api/v1/tasks/start",
        token=token,
        body={
            "taskType": "REFRESH_METADATA_MANUAL",
            "triggeredByCron": False,
            "options": {
                "refreshType": "BOOKS",
                "bookIds": unique_ids,
            },
        },
        timeout=20,
    )
    return {
        "ok": True,
        "message": f"Scheduled Grimmory metadata refresh for {len(unique_ids)} updated book(s).",
        "bookIds": unique_ids,
        "result": result,
    }


def run_heuristic_fix(
    *,
    threshold: Optional[float] = None,
    max_updates: Optional[int] = None,
    dry_run: bool = False,
    refresh_after_update: bool = False,
    progress_cb: Optional[Callable[[str, Dict[str, object]], None]] = None,
) -> Dict[str, object]:
    scan = scan_library(threshold, max_candidates=max_updates, progress_cb=progress_cb)
    if not scan.get("ok"):
        return scan

    results = []
    updated = 0
    updated_book_ids: List[int] = []
    if progress_cb:
        progress_cb(
            "start",
            {
                "mode": "heuristic",
                "totalCandidates": len(scan["candidates"]),
                "eligibleCount": sum(1 for item in scan["candidates"] if item.get("canAutoFix")),
                "dryRun": dry_run,
            },
        )
    for candidate in scan["candidates"]:
        if not candidate.get("canAutoFix"):
            continue
        suggestion = candidate.get("suggestion") or {}
        entry = {
            "id": candidate["id"],
            "title": candidate["title"],
            "sourceText": candidate["sourceText"],
            "suggestion": suggestion,
        }
        if progress_cb:
            progress_cb(
                "candidate",
                {
                    "id": candidate["id"],
                    "title": candidate["title"],
                    "state": "running",
                    "mode": "heuristic",
                },
            )
        if dry_run:
            entry["status"] = "would_update"
            results.append(entry)
            if progress_cb:
                progress_cb(
                    "candidate",
                    {
                        "id": candidate["id"],
                        "title": candidate["title"],
                        "state": "would_update",
                        "mode": "heuristic",
                    },
                )
            continue
        try:
            update_result = update_book(candidate["id"], suggestion)
        except Exception as exc:
            update_result = {
                "ok": False,
                "message": str(exc) or exc.__class__.__name__,
                "errorType": exc.__class__.__name__,
            }
        entry["status"] = "updated" if update_result.get("ok") else "failed"
        entry["result"] = update_result
        results.append(entry)
        if progress_cb:
            progress_cb(
                "candidate",
                {
                    "id": candidate["id"],
                    "title": candidate["title"],
                    "state": entry["status"],
                    "mode": "heuristic",
                    "result": update_result,
                },
            )
        if update_result.get("ok"):
            updated += 1
            updated_book_ids.append(int(candidate["id"]))
        if max_updates is not None and updated >= max_updates:
            break

    metadata_refresh = None
    if refresh_after_update and updated_book_ids:
        if progress_cb:
            progress_cb(
                "refresh",
                {
                    "mode": "heuristic",
                    "state": "refreshing_metadata",
                    "message": f"Scheduling Grimmory metadata refresh for {len(updated_book_ids)} updated book(s)",
                },
            )
        try:
            metadata_refresh = refresh_fixed_books(updated_book_ids)
        except Exception as exc:
            metadata_refresh = {
                "ok": False,
                "message": str(exc) or exc.__class__.__name__,
                "errorType": exc.__class__.__name__,
            }

    return {
        "ok": True,
        "dryRun": dry_run,
        "updatedCount": updated if not dry_run else 0,
        "plannedCount": len(results),
        "metadataRefresh": metadata_refresh,
        "results": results,
    }


def _require_llm_config() -> Optional[Dict[str, object]]:
    if LLM_BASE_URL and LLM_API_KEY and LLM_MODEL:
        return None
    return {
        "ok": False,
        "message": "Set LLM_BASE_URL, LLM_API_KEY, and LLM_MODEL in the stack .env before using LLM repair.",
    }


def _extract_json_object(text: str) -> Optional[Dict[str, object]]:
    text = (text or "").strip()
    if not text:
        return None
    fenced = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.DOTALL)
    if fenced:
        text = fenced.group(1)
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return None
    try:
        value = json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) else None


def _response_preview(value: object, limit: int = 500) -> str:
    text = str(value or "").replace("\r", " ").replace("\n", " ").strip()
    if len(text) <= limit:
        return text
    return f"{text[:limit]}..."


def _llm_content_from_payload(payload: object) -> object:
    if not isinstance(payload, dict):
        return None
    choices = payload.get("choices") or []
    if not choices or not isinstance(choices[0], dict):
        return None
    message = choices[0].get("message") or {}
    if not isinstance(message, dict):
        return None
    return message.get("content")


def _llm_content_to_text(content: object) -> str:
    if isinstance(content, list):
        return "".join(
            str(part.get("text", ""))
            for part in content
            if isinstance(part, dict)
        )
    return str(content or "")


def _llm_decide(candidate: Dict[str, object]) -> Dict[str, object]:
    prompt = {
        "current": {
            "id": candidate["id"],
            "score": candidate.get("score"),
            "title": candidate.get("title"),
            "authors": candidate.get("authors"),
            "seriesName": candidate.get("seriesName"),
            "fileName": candidate.get("fileName"),
            "sourceText": candidate.get("sourceText"),
            "reasons": candidate.get("reasons"),
        },
        "heuristicSuggestion": candidate.get("suggestion"),
        "bookTextSample": candidate.get("bookTextSample"),
        "sampleNote": candidate.get("sampleNote"),
    }
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {LLM_API_KEY}",
        "Content-Type": "application/json",
    }
    messages = [
        {
            "role": "system",
            "content": (
                "You repair bad library metadata. "
                "Return only one raw JSON object with keys: should_update, confidence, title, subtitle, authors, seriesName, seriesNumber, rationale. "
                "Do not use markdown fences, prose, comments, or explanations outside the JSON object. "
                "Use authors as an array of strings. "
                "Use the supplied opening book text as primary evidence to verify title and author. "
                "If the sample is missing, unrelated, or the identity remains uncertain, set should_update to false. "
                "If the sample conflicts with filenames or metadata, trust the book text only when it clearly identifies the work."
            ),
        },
        {
            "role": "user",
            "content": json.dumps(prompt, ensure_ascii=False),
        },
    ]
    last_error = "no response"
    last_content = ""
    for attempt in range(1, LLM_RETRY_ATTEMPTS + 1):
        body = {
            "model": LLM_MODEL,
            "temperature": 0.1 if attempt == 1 else 0,
            "messages": messages,
        }
        req = request.Request(
            f"{LLM_BASE_URL}/chat/completions",
            method="POST",
            headers=headers,
            data=json.dumps(body).encode("utf-8"),
        )
        try:
            with request.urlopen(req, timeout=LLM_TIMEOUT_SECONDS) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except (error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            last_error = f"{exc.__class__.__name__}: {exc}"
            if attempt >= LLM_RETRY_ATTEMPTS:
                break
            messages.append(
                {
                    "role": "user",
                    "content": (
                        f"The previous request failed with {last_error}. "
                        "Try again and return only the raw JSON object."
                    ),
                }
            )
            continue

        content = _llm_content_from_payload(payload)
        last_content = _llm_content_to_text(content)
        parsed = _extract_json_object(last_content)
        if parsed is not None:
            parsed["llmAttempts"] = attempt
            return parsed

        last_error = "response did not contain a parseable JSON object"
        if attempt < LLM_RETRY_ATTEMPTS:
            messages.append(
                {
                    "role": "assistant",
                    "content": last_content,
                }
            )
            messages.append(
                {
                    "role": "user",
                    "content": (
                        "That response was invalid for this tool. "
                        "Return only a single raw JSON object now. "
                        "No markdown, no code fence, no surrounding text."
                    ),
                }
            )
    preview = _response_preview(last_content)
    detail = f" Last response preview: {preview}" if preview else ""
    raise RuntimeError(
        f"LLM response failed after {LLM_RETRY_ATTEMPTS} attempt(s): {last_error}.{detail}"
    )


def _llm_needs_review(llm: Dict[str, object], min_confidence: float) -> bool:
    try:
        confidence = float(llm.get("confidence") or 0)
    except (TypeError, ValueError):
        confidence = 0.0
    authors = llm.get("authors")
    has_valid_author = (
        isinstance(authors, list)
        and any(
            isinstance(author, str)
            and _clean_text(author)
            and not UNKNOWN_AUTHOR_RE.match(_clean_text(author))
            for author in authors
        )
    )
    return (
        not llm.get("should_update")
        or confidence < min_confidence
        or not isinstance(llm.get("title"), str)
        or not _clean_text(llm.get("title"))
        or not has_valid_author
    )


def run_llm_fix(
    *,
    threshold: Optional[float] = None,
    book_ids: Optional[List[int]] = None,
    dry_run: bool = True,
    min_confidence: float = 0.9,
    max_updates: Optional[int] = None,
    refresh_after_update: bool = False,
    progress_cb: Optional[Callable[[str, Dict[str, object]], None]] = None,
) -> Dict[str, object]:
    config_error = _require_llm_config()
    if config_error:
        return config_error

    ids = {int(book_id) for book_id in (book_ids or [])}
    scan = scan_library(threshold, max_candidates=None if ids else max_updates, progress_cb=progress_cb)
    if not scan.get("ok"):
        return scan

    selected = []
    for candidate in scan["candidates"]:
        if ids and int(candidate["id"]) not in ids:
            continue
        selected.append(candidate)

    results = []
    updated = 0
    updated_book_ids: List[int] = []
    if progress_cb:
        progress_cb(
            "start",
            {
                "mode": "llm",
                "totalCandidates": len(scan["candidates"]),
                "selectedCount": len(selected),
                "dryRun": dry_run,
                "minConfidence": min_confidence,
            },
        )
    for candidate in selected:
        if progress_cb:
            progress_cb(
                "candidate",
                {
                    "id": candidate["id"],
                    "title": candidate["title"],
                    "state": "llm_deciding",
                    "mode": "llm",
                },
            )
        entry = {
            "id": candidate["id"],
            "title": candidate["title"],
            "sourceText": candidate["sourceText"],
        }
        try:
            primary_file = {"filePath": candidate.get("filePath")}
            sample, sample_error = _sample_book_text(primary_file)
            if sample:
                candidate["bookTextSample"] = sample
                candidate["sampleNote"] = f"Sampled opening {BOOK_SAMPLE_PAGES} PDF page(s) or equivalent EPUB text."
                llm = _llm_decide(candidate)
                if _llm_needs_review(llm, min_confidence):
                    expanded_sample, expanded_error = _sample_book_text(
                        primary_file,
                        page_count=BOOK_SAMPLE_RETRY_PAGES,
                        max_chars=BOOK_SAMPLE_RETRY_MAX_CHARS,
                    )
                    if expanded_sample and len(expanded_sample) > len(sample):
                        candidate["bookTextSample"] = expanded_sample
                        candidate["sampleNote"] = (
                            f"Initial sample was inconclusive; retried with the first {BOOK_SAMPLE_RETRY_PAGES} PDF pages "
                            "or a larger opening EPUB excerpt."
                        )
                        try:
                            retry_llm = _llm_decide(candidate)
                            retry_llm["sampleRetry"] = True
                            llm = retry_llm
                        except Exception as retry_exc:
                            llm["sampleRetryError"] = str(retry_exc) or retry_exc.__class__.__name__
                            candidate["sampleNote"] += " The larger-sample LLM request failed; the first result was retained."
                    else:
                        candidate["sampleNote"] = (
                            f"Initial {BOOK_SAMPLE_PAGES}-page/equivalent sample was inconclusive; the "
                            f"{BOOK_SAMPLE_RETRY_PAGES}-page/equivalent retry provided no additional readable text. "
                            f"{expanded_error or 'No larger sample was available.'}"
                        )
            else:
                expanded_sample, expanded_error = _sample_book_text(
                    primary_file,
                    page_count=BOOK_SAMPLE_RETRY_PAGES,
                    max_chars=BOOK_SAMPLE_RETRY_MAX_CHARS,
                )
                if expanded_sample:
                    candidate["bookTextSample"] = expanded_sample
                    candidate["sampleNote"] = (
                        f"Initial sample unavailable; sampled the first {BOOK_SAMPLE_RETRY_PAGES} PDF pages "
                        "or a larger opening EPUB excerpt on retry."
                    )
                    llm = _llm_decide(candidate)
                else:
                    candidate["bookTextSample"] = ""
                    candidate["sampleNote"] = (
                        f"Initial sample unavailable ({sample_error}); {BOOK_SAMPLE_RETRY_PAGES}-page/equivalent retry failed "
                        f"({expanded_error})."
                    )
                    llm = {
                        "should_update": False,
                        "confidence": 0,
                        "title": candidate.get("title"),
                        "authors": [],
                        "rationale": "No readable book text was available after the larger-sample retry.",
                    }
        except Exception as exc:
            entry["status"] = "failed"
            entry["error"] = str(exc) or exc.__class__.__name__
            results.append(entry)
            if progress_cb:
                progress_cb(
                    "candidate",
                    {
                        "id": candidate["id"],
                        "title": candidate["title"],
                        "state": "needs_review",
                        "mode": "llm",
                        "result": {
                            "ok": False,
                            "message": entry["error"],
                            "errorType": exc.__class__.__name__,
                        },
                        "sampleNote": candidate.get("sampleNote"),
                    },
                )
            continue

        try:
            confidence = float(llm.get("confidence") or 0)
        except (TypeError, ValueError):
            confidence = 0.0
        entry["llm"] = llm
        if _llm_needs_review(llm, min_confidence):
            entry["status"] = "needs_review"
            entry["reviewReason"] = llm.get("rationale") or "LLM could not confidently identify this book or returned incomplete title/author fields."
            results.append(entry)
            if progress_cb:
                progress_cb(
                    "candidate",
                    {
                        "id": candidate["id"],
                        "title": candidate["title"],
                        "state": "needs_review",
                        "mode": "llm",
                        "llm": llm,
                        "reviewReason": entry["reviewReason"],
                        "sampleNote": candidate.get("sampleNote"),
                    },
                )
            continue

        proposed = {
            "title": llm.get("title"),
            "subtitle": llm.get("subtitle"),
            "authors": llm.get("authors") or [],
            "seriesName": llm.get("seriesName"),
            "seriesNumber": llm.get("seriesNumber"),
        }
        if dry_run:
            entry["status"] = "would_update"
            entry["proposed"] = proposed
            results.append(entry)
            if progress_cb:
                progress_cb(
                    "candidate",
                    {
                        "id": candidate["id"],
                        "title": candidate["title"],
                        "state": "would_update",
                        "mode": "llm",
                        "llm": llm,
                        "sampleNote": candidate.get("sampleNote"),
                    },
                )
            continue

        if progress_cb:
            progress_cb(
                "candidate",
                {
                    "id": candidate["id"],
                    "title": candidate["title"],
                    "state": "updating",
                    "mode": "llm",
                    "llm": llm,
                },
            )
        try:
            update_result = update_book(int(candidate["id"]), proposed)
        except Exception as exc:
            update_result = {
                "ok": False,
                "message": str(exc) or exc.__class__.__name__,
                "errorType": exc.__class__.__name__,
            }
        entry["status"] = "updated" if update_result.get("ok") else "failed"
        entry["result"] = update_result
        results.append(entry)
        if progress_cb:
            progress_cb(
                "candidate",
                {
                    "id": candidate["id"],
                    "title": candidate["title"],
                    "state": entry["status"],
                    "mode": "llm",
                    "llm": llm,
                    "result": update_result,
                },
            )
        if update_result.get("ok"):
            updated += 1
            updated_book_ids.append(int(candidate["id"]))
        if max_updates is not None and updated >= max_updates:
            break

    metadata_refresh = None
    if refresh_after_update and updated_book_ids:
        if progress_cb:
            progress_cb(
                "refresh",
                {
                    "mode": "llm",
                    "state": "refreshing_metadata",
                    "message": f"Scheduling Grimmory metadata refresh for {len(updated_book_ids)} updated book(s)",
                },
            )
        try:
            metadata_refresh = refresh_fixed_books(updated_book_ids)
        except Exception as exc:
            metadata_refresh = {
                "ok": False,
                "message": str(exc) or exc.__class__.__name__,
                "errorType": exc.__class__.__name__,
            }

    return {
        "ok": True,
        "dryRun": dry_run,
        "minConfidence": min_confidence,
        "selectedCount": len(selected),
        "updatedCount": updated if not dry_run else 0,
        "failedCount": sum(1 for item in results if item.get("status") == "failed"),
        "skippedCount": sum(1 for item in results if item.get("status") == "skipped"),
        "needsReviewCount": sum(1 for item in results if item.get("status") == "needs_review"),
        "plannedCount": len(results),
        "metadataRefresh": metadata_refresh,
        "results": results,
    }


def safe_float(value: object, fallback: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def safe_int(value: object) -> Optional[int]:
    if value in (None, ""):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
