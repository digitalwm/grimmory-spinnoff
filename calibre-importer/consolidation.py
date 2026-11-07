"""Verified consolidation of legacy EPUB folders into Grimmory BookDrop."""
import hashlib
import difflib
import math
import os
import re
import shutil
import subprocess
import threading
import tempfile
import unicodedata
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict
from urllib.parse import unquote

from grimmory_repair import (
    BOOK_SAMPLE_PAGES,
    BOOK_SAMPLE_MAX_CHARS,
    LLM_MODEL,
    _llm_decide,
    _require_llm_config,
    _text_from_html,
)


TARGET_ROOT = Path(os.environ.get("GRIMMORY_IMPORT_TARGET", "/target")).resolve()
SOURCE_ROOTS = {
    "epub": ("epub", Path(os.environ.get("CONSOLIDATION_EPUB", "/sources/epub"))),
    "epub_clean": ("epub_clean", Path(os.environ.get("CONSOLIDATION_CLEAN", "/sources/epub_clean"))),
    "epub_managed": ("epub-managed library", Path(os.environ.get("CONSOLIDATION_MANAGED", "/sources/epub-managed"))),
}
LOCK = threading.Lock()
ITEMS: Dict[str, dict] = {}
SCAN_META = {"ready": False, "total": 0, "errors": 0}
PREPARE_STATE = {"running": False, "ids": [], "total": 0, "processed": 0, "prepared": 0, "needsReview": 0, "failed": 0, "autoImport": False, "autoImportEligible": 0, "autoImportHeld": 0, "autoImportError": None, "message": "Idle"}
PREPARE_LOCK = threading.Lock()
IMPORT_LOCK = threading.Lock()
IMPORT_STATE = {"running": False, "ids": [], "total": 0, "processed": 0, "moved": 0, "failed": 0, "message": "Idle", "result": None}
IMPORT_START_LOCK = threading.Lock()
# Non-audio book formats supported by Grimmory's BookDrop/library ingestion.
SUPPORTED = {".epub", ".pdf", ".mobi", ".azw", ".azw3", ".fb2", ".cbz", ".cbr", ".cb7"}


def _id(source: str, relative: str) -> str:
    return hashlib.sha256(f"{source}\0{relative}".encode()).hexdigest()[:24]


def scan_sources() -> dict:
    items: Dict[str, dict] = {}
    errors = 0
    for source, (label, configured_root) in SOURCE_ROOTS.items():
        root = configured_root.resolve()
        if not root.is_dir():
            errors += 1
            continue
        for directory, dirnames, filenames in os.walk(root, followlinks=False):
            dirnames[:] = [name for name in dirnames if not (Path(directory) / name).is_symlink()]
            for name in filenames:
                path = Path(directory) / name
                if path.suffix.lower() not in SUPPORTED or path.is_symlink():
                    continue
                try:
                    stat = path.stat()
                    relative = path.relative_to(root).as_posix()
                except OSError:
                    errors += 1
                    continue
                item_id = _id(source, relative)
                items[item_id] = {
                    "id": item_id, "source": source, "sourceLabel": label,
                    "relativePath": relative, "name": path.name,
                    "size": stat.st_size, "modified": int(stat.st_mtime),
                }
    source_counts = {key: 0 for key in SOURCE_ROOTS}
    for item in items.values():
        source_counts[item["source"]] += 1
    with LOCK:
        ITEMS.clear()
        ITEMS.update(items)
        SCAN_META.update(ready=True, total=len(items), errors=errors, sourceCounts=source_counts)
    return {"ok": True, **SCAN_META}


def list_items(offset: int = 0, limit: int = 100, item_ids: list[str] | None = None) -> dict:
    with LOCK:
        rows = list(ITEMS.values())
        meta = dict(SCAN_META)
    rows.sort(key=lambda row: (row["sourceLabel"], row["relativePath"].casefold()))
    if item_ids is not None:
        wanted = set(item_ids)
        rows = [row for row in rows if row["id"] in wanted]
        return {**meta, "offset": 0, "limit": len(rows), "items": rows}
    return {**meta, "offset": offset, "limit": limit, "items": rows[offset:offset + limit]}


def prepare_status() -> dict:
    return dict(PREPARE_STATE)


def import_status() -> dict:
    return dict(IMPORT_STATE)


def delete_selected(ids: list[str]) -> dict:
    if not ids or len(ids) > 100:
        raise ValueError("Select between 1 and 100 book files per delete batch.")
    if PREPARE_STATE["running"] or not IMPORT_LOCK.acquire(blocking=False):
        raise ValueError("Wait for LLM preparation or an import to finish before deleting source files.")
    deleted, failed = [], []
    try:
        with LOCK:
            selected = [(item_id, ITEMS.get(item_id)) for item_id in dict.fromkeys(ids)]
        for item_id, item in selected:
            if not item:
                failed.append({"id": item_id, "message": "Rescan the source; this selection has expired."})
                continue
            try:
                source = _source_path(item)
                if source.is_symlink() or not source.is_file() or source.suffix.lower() not in SUPPORTED:
                    raise ValueError("Selected path is no longer a supported book file.")
                stat = source.stat()
                if stat.st_size != item["size"] or int(stat.st_mtime) != item["modified"]:
                    raise ValueError("Source changed since the inventory scan; rescan before deleting.")
                source.unlink()
                with LOCK:
                    ITEMS.pop(item_id, None)
                    counts = {key: 0 for key in SOURCE_ROOTS}
                    for current in ITEMS.values():
                        counts[current["source"]] += 1
                    SCAN_META.update(total=len(ITEMS), sourceCounts=counts)
                deleted.append({"id": item_id, "name": item["name"]})
            except Exception as exc:
                failed.append({"id": item_id, "name": item["name"], "message": str(exc)})
        return {"ok": not failed, "deleted": deleted, "failed": failed}
    finally:
        IMPORT_LOCK.release()


def _source_path(item: dict) -> Path:
    root = SOURCE_ROOTS[item["source"]][1].resolve()
    candidate = (root / item["relativePath"]).resolve()
    candidate.relative_to(root)
    return candidate


def _digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _epub_content_digest(path: Path) -> str:
    """Hash EPUB resources while excluding the OPF package metadata Calibre rewrites."""
    with zipfile.ZipFile(path) as archive:
        container = ET.fromstring(archive.read("META-INF/container.xml"))
        package_path = next(node.attrib["full-path"] for node in container.iter() if node.tag.endswith("rootfile"))
        digest = hashlib.sha256()
        for name in sorted(archive.namelist()):
            if name == package_path:
                continue
            digest.update(name.encode("utf-8"))
            digest.update(b"\0")
            digest.update(archive.read(name))
            digest.update(b"\0")
        return digest.hexdigest()


def _read_file_metadata(path: Path) -> dict:
    result = subprocess.run(["ebook-meta", str(path)], capture_output=True, text=True, timeout=30, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "Could not read book metadata")
    values = {}
    for line in result.stdout.splitlines():
        match = re.match(r"^\s*(Title|Author\(s\))\s*:\s*(.*?)\s*$", line)
        if match:
            values["title" if match.group(1) == "Title" else "authors"] = match.group(2)
    return values


def _sample_source_text(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".epub":
        return _sample_epub(path)
    if suffix == ".pdf":
        result = subprocess.run(
            ["pdftotext", "-f", "1", "-l", str(BOOK_SAMPLE_PAGES), "-layout", str(path), "-"],
            capture_output=True, text=True, timeout=30, check=False,
        )
        if result.returncode:
            return ""
        return re.sub(r"\s+", " ", result.stdout).strip()[:BOOK_SAMPLE_MAX_CHARS]
    return ""


def _safe_filename_part(value: str, limit: int = 120) -> str:
    value = unicodedata.normalize("NFKC", str(value or ""))
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', " ", value)
    value = re.sub(r"\s+", " ", value).strip(" .")
    value = value.rstrip(" .")
    while value and len(value.encode("utf-8")) > limit:
        value = value[:-1].rstrip(" .")
    return value or "Unknown"


def _canonical_filename(title: str, authors: list[str], suffix: str) -> str:
    author = _safe_filename_part(" & ".join(authors))
    clean_title = _safe_filename_part(title)
    return f"{author} - {clean_title}{suffix.lower()}"


def _sample_epub(path: Path, max_chars: int = 12000) -> str:
    with zipfile.ZipFile(path) as archive:
        container = ET.fromstring(archive.read("META-INF/container.xml"))
        rootfile = next(node.attrib["full-path"] for node in container.iter() if node.tag.endswith("rootfile"))
        opf = ET.fromstring(archive.read(rootfile))
        manifest = {node.attrib.get("id"): node.attrib.get("href") for node in opf.iter() if node.tag.endswith("item")}
        spine = [node.attrib.get("idref") for node in opf.iter() if node.tag.endswith("itemref")]
        base = Path(rootfile).parent
        chunks = []
        for item_id in spine:
            href = manifest.get(item_id)
            if not href:
                continue
            member = str(base / unquote(href))
            member = str(Path(member))
            if member.startswith("../") or member.startswith("/"):
                continue
            info = archive.getinfo(member)
            if info.file_size > 2_000_000:
                continue
            text = _text_from_html(archive.read(member))
            if text:
                chunks.append(text)
            if len(" ".join(chunks)) >= max_chars:
                break
        return " ".join(chunks)[:max_chars]


def _normalized_match_text(value: str) -> str:
    value = unicodedata.normalize("NFKD", str(value or "")).encode("ascii", "ignore").decode().casefold()
    return " ".join(re.findall(r"[a-z0-9]+", value))


def _metadata_author_names(value: str) -> list[str]:
    value = re.sub(r"\s*\[[^]]*\]", "", str(value or ""))
    return [_normalized_match_text(part) for part in re.split(r"\s*(?:&|;)\s*", value) if _normalized_match_text(part)]


def _strong_filename_metadata_match(item: dict, metadata: dict) -> bool:
    """Trust metadata without readable text only when title and author agree across all sources."""
    stem = Path(item["name"]).stem
    if " - " not in stem:
        return False
    filename_title, filename_author = stem.rsplit(" - ", 1)
    current_title = str(metadata.get("title") or "")
    current_authors = _metadata_author_names(metadata.get("authors", ""))
    file_author = _normalized_match_text(filename_author)
    folder_author = _normalized_match_text(Path(item.get("relativePath", "")).parts[0] if item.get("relativePath") else "")
    if not current_title or not current_authors or not file_author or not folder_author:
        return False
    if file_author not in current_authors or folder_author not in current_authors:
        return False

    # A leading volume number belongs to the displayed title, but should not make
    # an otherwise matching filename and embedded title look different.
    filename_title = re.sub(r"^\s*\d{1,4}[.)\s_-]+", "", filename_title)
    current_title = re.sub(r"^\s*\d{1,4}[.)\s_-]+", "", current_title)
    normalized_filename_title = _normalized_match_text(filename_title)
    normalized_current_title = _normalized_match_text(current_title)
    if min(len(normalized_filename_title), len(normalized_current_title)) < 8:
        return False
    return difflib.SequenceMatcher(None, normalized_filename_title, normalized_current_title).ratio() >= 0.95


def _prepare_one(item_id: str, item: dict) -> dict:
    path = _source_path(item)
    if not path.is_file():
        raise FileNotFoundError("Source book file is no longer available")
    metadata = _read_file_metadata(path)
    sample = _sample_source_text(path)
    sample_note = None if sample else (
        "No readable opening text was extracted." if path.suffix.lower() in {".epub", ".pdf"}
        else f"Text sampling is not enabled for {path.suffix.lower()} files; use embedded metadata and filename for review."
    )
    proposal = _llm_decide({
        "id": item_id,
        "score": None,
        "title": metadata.get("title"),
        "authors": metadata.get("authors"),
        "seriesName": None,
        "fileName": item["name"],
        "sourceText": item["relativePath"],
        "reasons": [],
        "suggestion": None,
        "bookTextSample": sample,
        "sampleNote": sample_note,
    })
    title = str(proposal.get("title") or "").strip()
    authors = proposal.get("authors")
    if isinstance(authors, str):
        authors = [part.strip() for part in re.split(r"\s*(?:;|&)\s*", authors) if part.strip()]
    if not isinstance(authors, list):
        authors = []
    authors = [str(author).strip() for author in authors if str(author).strip()]
    try:
        confidence = float(proposal.get("confidence") or 0)
    except (TypeError, ValueError):
        confidence = 0.0
    llm_confidence = confidence
    confidence_basis = "llm"
    rationale = str(proposal.get("rationale") or "")
    if not sample and confidence < 0.5 and _strong_filename_metadata_match(item, metadata):
        # Use the already embedded, independently corroborated values when the
        # book itself contains no extractable text (common for image based comics).
        title = str(metadata.get("title") or "").strip()
        authors = [re.sub(r"\s*\[[^]]*\]", "", author).strip() for author in re.split(r"\s*(?:&|;)\s*", metadata.get("authors", ""))]
        authors = [author for author in authors if author]
        confidence = 0.65
        confidence_basis = "filename_metadata_match"
        rationale = "No readable text sample was available. The filename, embedded title and author, and author folder agree; existing embedded metadata will be retained."
    if not math.isfinite(confidence) or confidence < 0.5:
        short_title = re.split(r"[(:]", title, maxsplit=1)[0].strip()
        if short_title != title:
            title = short_title
            rationale = f"{rationale} Subtitle or parenthetical text was omitted from the title for manual verification.".strip()
    return {
        "title": title,
        "authors": authors,
        "confidence": confidence,
        "llmConfidence": llm_confidence,
        "confidenceBasis": confidence_basis,
        "rationale": rationale,
        "shouldUpdate": bool(proposal.get("should_update")),
        "sampleAvailable": bool(sample),
        "sourceHash": _digest(path),
        "_currentMetadata": metadata,
    }


def prepare_selected(ids: list[str], auto_import: bool = False) -> dict:
    if not ids or len(ids) > 500:
        raise ValueError("Select between 1 and 500 supported book files for LLM preparation.")
    config_error = _require_llm_config()
    if config_error:
        raise ValueError(str(config_error["message"]))
    if not PREPARE_LOCK.acquire(blocking=False):
        raise RuntimeError("An LLM metadata preparation is already running.")
    if IMPORT_LOCK.locked() or IMPORT_STATE["running"]:
        PREPARE_LOCK.release()
        raise RuntimeError("An import or source deletion is already running.")
    try:
        with LOCK:
            selected = [(item_id, ITEMS.get(item_id)) for item_id in dict.fromkeys(ids)]
        if any(not item for _, item in selected):
            raise ValueError("Rescan the source; a selected item has expired.")
        PREPARE_STATE.update(running=True, ids=[item_id for item_id, _ in selected], total=len(selected), processed=0, prepared=0, needsReview=0, failed=0, autoImport=auto_import, autoImportEligible=0, autoImportHeld=0, autoImportError=None, message=f"Preparing with {LLM_MODEL}")

        def run() -> None:
            try:
                for item_id, item in selected:
                    try:
                        proposal = _prepare_one(item_id, item)
                        item["prepared"] = True
                        item["currentMetadata"] = proposal.pop("_currentMetadata", {})
                        item["proposal"] = proposal
                        if proposal["title"] and proposal["authors"] and proposal["confidence"] >= 0.5:
                            PREPARE_STATE["prepared"] += 1
                        else:
                            PREPARE_STATE["needsReview"] += 1
                    except Exception as exc:
                        item["prepared"] = True
                        item["proposal"] = {"title": "", "authors": [], "confidence": 0, "rationale": str(exc), "shouldUpdate": False, "sampleAvailable": False}
                        PREPARE_STATE["failed"] += 1
                    PREPARE_STATE["processed"] += 1
                    PREPARE_STATE["message"] = f"Prepared {PREPARE_STATE['processed']} of {PREPARE_STATE['total']} book files"
            finally:
                PREPARE_STATE["running"] = False
                PREPARE_STATE["message"] = "LLM preparation complete"
                PREPARE_LOCK.release()
            if auto_import:
                entries = []
                for item_id, item in selected:
                    proposal = item.get("proposal") or {}
                    title = str(proposal.get("title") or "").strip()
                    authors = proposal.get("authors") or []
                    if isinstance(authors, str):
                        authors = [part.strip() for part in authors.split(";") if part.strip()]
                    authors = [str(author).strip() for author in authors if str(author).strip()]
                    if item.get("prepared") and float(proposal.get("confidence") or 0) >= 0.5 and title and authors:
                        entries.append({"id": item_id, "title": title, "authors": authors})
                PREPARE_STATE["autoImportEligible"] = len(entries)
                PREPARE_STATE["autoImportHeld"] = len(selected) - len(entries)
                if entries:
                    try:
                        start_import(entries)
                    except Exception as exc:
                        PREPARE_STATE["autoImportError"] = str(exc)

        threading.Thread(target=run, daemon=True).start()
    except Exception:
        PREPARE_STATE.update(running=False, message="Preparation could not be started")
        if PREPARE_LOCK.locked():
            PREPARE_LOCK.release()
        raise
    return {"ok": True, "total": len(selected), "message": "LLM preparation started."}


def import_approved(entries: list[dict], progress_cb=None) -> dict:
    if not entries or len(entries) > 500:
        raise ValueError("Approve between 1 and 500 prepared book files per import batch.")
    if PREPARE_STATE["running"] or not IMPORT_LOCK.acquire(blocking=False):
        raise ValueError("An import or source deletion is already running.")
    imported, failed = [], []
    with LOCK:
        selected = [(entry.get("id"), ITEMS.get(entry.get("id")), entry) for entry in entries]
    try:
      for item_id, item, entry in selected:
        try:
            if not item or not item.get("prepared"):
                raise ValueError("Prepare this item with the LLM before importing.")
            title = str(entry.get("title") or "").strip()
            authors = entry.get("authors") or []
            if isinstance(authors, str):
                authors = [part.strip() for part in authors.split(";") if part.strip()]
            authors = [str(author).strip() for author in authors if str(author).strip()]
            if not title or not authors:
                raise ValueError("A title and at least one author are required.")
            source = _source_path(item)
            if not source.is_file():
                raise FileNotFoundError("Source book file is no longer available")
            if source.suffix.lower() not in SUPPORTED:
                raise ValueError("This book file format is not supported for import.")
            source_hash = item.get("proposal", {}).get("sourceHash")
            if not source_hash or _digest(source) != source_hash:
                raise ValueError("Source book file changed after LLM preparation; prepare it again before importing.")

            folder = TARGET_ROOT / "Consolidated Imports"
            folder.mkdir(parents=True, exist_ok=True)
            target_owner = TARGET_ROOT.stat()
            if os.geteuid() == 0:
                os.chown(folder, target_owner.st_uid, target_owner.st_gid)

            source_content_hash = None

            def matches_source_content(candidate: Path) -> bool:
                nonlocal source_content_hash
                if not candidate.is_file():
                    return False
                if _digest(candidate) == source_hash:
                    return True
                if source.suffix.lower() != ".epub":
                    return False
                if source_content_hash is None:
                    source_content_hash = _epub_content_digest(source)
                try:
                    return _epub_content_digest(candidate) == source_content_hash
                except (OSError, zipfile.BadZipFile, KeyError, ET.ParseError):
                    return False

            destination_name = _canonical_filename(title, authors, source.suffix)
            destination = folder / destination_name
            exact_duplicate = matches_source_content(destination)
            if destination.exists() and not exact_duplicate:
                exact_duplicate = False
                destination = None
                for hash_length in (10, 16, 24, 32, 48, 64):
                    candidate = folder / f"{Path(destination_name).stem}__{source_hash[:hash_length]}{source.suffix.lower()}"
                    if not candidate.exists():
                        destination = candidate
                        break
                    if matches_source_content(candidate):
                        destination = candidate
                        exact_duplicate = True
                        break
                if destination is None:
                    suffix_index = 2
                    while destination is None:
                        candidate = folder / f"{Path(destination_name).stem}__{source_hash}-{suffix_index}{source.suffix.lower()}"
                        if not candidate.exists():
                            destination = candidate
                            break
                        if matches_source_content(candidate):
                            destination = candidate
                            exact_duplicate = True
                            break
                        suffix_index += 1

            if exact_duplicate:
                existing = _read_file_metadata(destination)
                desired_authors = " & ".join(authors)
                if existing.get("title", "").casefold() != title.casefold() or existing.get("authors", "").casefold() != desired_authors.casefold():
                    _rewrite_metadata_atomic(destination, title, authors)
                verified = _read_file_metadata(destination)
            else:
                with tempfile.NamedTemporaryFile(prefix="grimmory-import-", suffix=source.suffix.lower(), dir="/tmp", delete=False) as temp_file:
                    temporary = Path(temp_file.name)
                try:
                    shutil.copy2(source, temporary)
                    if _digest(temporary) != source_hash:
                        raise IOError("Staged copy failed hash verification")
                    result = subprocess.run(["ebook-meta", str(temporary), "--title", title, "--authors", " & ".join(authors)], capture_output=True, text=True, timeout=60, check=False)
                    if result.returncode:
                        raise RuntimeError(result.stderr.strip() or "Calibre could not write metadata")
                    verified = _read_file_metadata(temporary)
                    if not verified.get("title") or not verified.get("authors"):
                        raise RuntimeError("Metadata verification failed after writing the book file")
                    pending = destination.with_name(f".{destination.name}.pending")
                    try:
                        shutil.copy2(temporary, pending)
                        if _digest(pending) != _digest(temporary):
                            raise IOError("Final library copy failed hash verification")
                        pending.replace(destination)
                        if os.geteuid() == 0:
                            os.chown(destination, target_owner.st_uid, target_owner.st_gid)
                    finally:
                        pending.unlink(missing_ok=True)
                finally:
                    temporary.unlink(missing_ok=True)

            if os.geteuid() == 0:
                os.chown(destination, target_owner.st_uid, target_owner.st_gid)

            # Cross-filesystem move: publish the verified copy first, then remove its source.
            try:
                source.unlink()
            except OSError as exc:
                if not exact_duplicate:
                    destination.unlink(missing_ok=True)
                raise OSError(f"Source deletion failed; the newly published copy was rolled back: {exc}") from exc
            imported.append({"id": item_id, "name": item["name"], "title": verified["title"], "authors": verified["authors"], "destination": str(destination.relative_to(TARGET_ROOT)), "alreadyInTarget": exact_duplicate, "sourceRemoved": True})
            with LOCK:
                ITEMS.pop(item_id, None)
                counts = {key: 0 for key in SOURCE_ROOTS}
                for current in ITEMS.values():
                    counts[current["source"]] += 1
                SCAN_META.update(total=len(ITEMS), sourceCounts=counts)
        except Exception as exc:
            failed.append({"id": item_id, "name": item.get("name") if item else None, "message": str(exc)})
        if progress_cb:
            progress_cb(len(imported) + len(failed), len(imported), len(failed))
    finally:
        IMPORT_LOCK.release()
    return {"ok": not failed, "imported": imported, "failed": failed}


def start_import(entries: list[dict]) -> dict:
    if not entries or len(entries) > 500:
        raise ValueError("Approve between 1 and 500 prepared book files per import batch.")
    if not IMPORT_START_LOCK.acquire(blocking=False):
        raise RuntimeError("Wait for the active preparation or import to finish.")
    if PREPARE_STATE["running"] or IMPORT_STATE["running"] or IMPORT_LOCK.locked():
        IMPORT_START_LOCK.release()
        raise RuntimeError("Wait for the active preparation or import to finish.")
    IMPORT_STATE.update(running=True, ids=[entry["id"] for entry in entries], total=len(entries), processed=0, moved=0, failed=0, message="Moving verified book files into BookDrop", result=None)

    def progress(processed: int, moved: int, failed: int) -> None:
        IMPORT_STATE.update(processed=processed, moved=moved, failed=failed, message=f"Moved {moved} of {len(entries)} selected book files")

    def run() -> None:
        try:
            result = import_approved(entries, progress_cb=progress)
            IMPORT_STATE["result"] = result
        except Exception as exc:
            IMPORT_STATE["result"] = {"ok": False, "imported": [], "failed": [{"message": str(exc)}]}
            IMPORT_STATE["failed"] = len(entries)
        finally:
            IMPORT_STATE["running"] = False
            IMPORT_STATE["message"] = "Import complete"
            IMPORT_START_LOCK.release()

    threading.Thread(target=run, daemon=True).start()
    return {"ok": True, "total": len(entries), "message": "Verified move started."}


def _rewrite_metadata_atomic(target: Path, title: str, authors: list[str]) -> None:
    with tempfile.NamedTemporaryFile(prefix="grimmory-meta-", suffix=target.suffix.lower(), dir="/tmp", delete=False) as temp_file:
        temporary = Path(temp_file.name)
    pending = target.with_name(f".{target.name}.metadata-pending")
    try:
        shutil.copy2(target, temporary)
        result = subprocess.run(["ebook-meta", str(temporary), "--title", title, "--authors", " & ".join(authors)], capture_output=True, text=True, timeout=60, check=False)
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or "Calibre could not update duplicate metadata")
        verified = _read_file_metadata(temporary)
        if not verified.get("title") or not verified.get("authors"):
            raise RuntimeError("Duplicate book metadata verification failed")
        shutil.copy2(temporary, pending)
        if _digest(temporary) != _digest(pending):
            raise IOError("Duplicate metadata copy failed hash verification")
        pending.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
        pending.unlink(missing_ok=True)
