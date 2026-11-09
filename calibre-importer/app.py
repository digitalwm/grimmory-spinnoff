import json
import hmac
import os
import threading
import traceback
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse
from urllib.parse import parse_qs
from consolidation import delete_selected, import_status, list_items, prepare_selected, prepare_status, scan_sources, start_import

from grimmory_repair import (
    GRIMMORY_LOW_SCORE_THRESHOLD,
    config_status as grimmory_config_status,
    delete_books,
    identify_duplicate_group,
    run_heuristic_fix,
    run_llm_fix,
    safe_float,
    safe_int,
    scan_library,
    update_book,
)


BASE_DIR = Path("/app")
TEMPLATES_DIR = BASE_DIR / "templates"
STATIC_DIR = BASE_DIR / "static"
APP_PORT = int(os.environ.get("APP_PORT", "8090"))
RUN_LOCK = threading.Lock()
STATE_LOCK = threading.Lock()
DEFAULT_MAX_UPDATES = 25
REVIEW_QUEUE_PATH = Path(os.environ.get("REVIEW_QUEUE_PATH", "/data/llm-review-queue.json"))
ACTIVE_RUN = {
    "running": False,
    "mode": None,
    "dryRun": False,
    "startedAt": None,
    "finishedAt": None,
    "currentBookId": None,
    "currentTitle": None,
    "state": "idle",
    "phase": "idle",
    "processed": 0,
    "scanProcessed": 0,
    "scanTotal": 0,
    "candidateCount": 0,
    "selectedCount": 0,
    "updatedCount": 0,
    "failedCount": 0,
    "skippedCount": 0,
    "needsReviewCount": 0,
    "items": [],
    "itemStates": {},
    "lastResult": None,
    "message": "Idle",
}
LAST_SCAN_RESULT = None


def update_run(**values: object) -> None:
    with STATE_LOCK:
        ACTIVE_RUN.update(values)


def snapshot_run() -> dict:
    with STATE_LOCK:
        return json.loads(json.dumps(ACTIVE_RUN))


def reset_run(mode: str, dry_run: bool) -> None:
    update_run(
        running=True,
        mode=mode,
        dryRun=dry_run,
        startedAt=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        finishedAt=None,
        currentBookId=None,
        currentTitle=None,
        state="starting",
        phase="starting",
        processed=0,
        scanProcessed=0,
        scanTotal=0,
        candidateCount=0,
        selectedCount=0,
        updatedCount=0,
        failedCount=0,
        skippedCount=0,
        needsReviewCount=0,
        items=[],
        itemStates={},
        lastResult=None,
        message=f"{mode} run starting",
    )


def progress_callback(event: str, payload: dict) -> None:
    with STATE_LOCK:
        if event == "scan":
            ACTIVE_RUN["state"] = payload.get("state", "scanning")
            ACTIVE_RUN["phase"] = payload.get("phase", ACTIVE_RUN.get("phase", "scanning"))
            ACTIVE_RUN["message"] = payload.get("message", "Scanning Grimmory library")
            if "processed" in payload:
                ACTIVE_RUN["scanProcessed"] = payload.get("processed", 0)
            if "total" in payload:
                ACTIVE_RUN["scanTotal"] = payload.get("total", 0)
            if "candidateCount" in payload:
                ACTIVE_RUN["candidateCount"] = payload.get("candidateCount", 0)
            ACTIVE_RUN["currentBookId"] = payload.get("id")
            ACTIVE_RUN["currentTitle"] = payload.get("title")
            return
        if event == "start":
            ACTIVE_RUN["state"] = "running"
            ACTIVE_RUN["selectedCount"] = payload.get("eligibleCount", payload.get("selectedCount", 0))
            ACTIVE_RUN["message"] = f"{payload.get('mode')} run active"
            return
        if event == "refresh":
            ACTIVE_RUN["state"] = payload.get("state", "refreshing_metadata")
            ACTIVE_RUN["phase"] = "refreshing_metadata"
            ACTIVE_RUN["message"] = payload.get("message", "Scheduling Grimmory metadata refresh")
            return
        if event == "candidate":
            book_id = str(payload.get("id"))
            state = payload.get("state")
            title = payload.get("title")
            ACTIVE_RUN["currentBookId"] = payload.get("id")
            ACTIVE_RUN["currentTitle"] = title
            ACTIVE_RUN["itemStates"][book_id] = {
                "id": payload.get("id"),
                "title": title,
                "state": state,
                "mode": payload.get("mode"),
                "reason": payload.get("reviewReason") or (payload.get("result") or {}).get("message"),
                "sampleNote": payload.get("sampleNote"),
                "proposed": payload.get("llm"),
            }
            if state in {"needs_review", "failed"} and payload.get("mode") == "llm":
                ACTIVE_RUN["needsReviewCount"] = int(ACTIVE_RUN.get("needsReviewCount", 0)) + 1
                save_review_item(ACTIVE_RUN["itemStates"][book_id])
            elif state == "updated" and payload.get("mode") == "llm":
                clear_review_item(book_id)
            if state in {"updated", "failed", "skipped", "would_update", "needs_review"}:
                ACTIVE_RUN["processed"] = int(ACTIVE_RUN.get("processed", 0)) + 1
                if state == "updated":
                    ACTIVE_RUN["updatedCount"] = int(ACTIVE_RUN.get("updatedCount", 0)) + 1
                elif state == "failed":
                    ACTIVE_RUN["failedCount"] = int(ACTIVE_RUN.get("failedCount", 0)) + 1
                elif state == "skipped":
                    ACTIVE_RUN["skippedCount"] = int(ACTIVE_RUN.get("skippedCount", 0)) + 1
            ACTIVE_RUN["items"] = list(ACTIVE_RUN["itemStates"].values())[-120:]


def finish_run(result: dict) -> None:
    counts = result.get("counts") or {}
    update_run(
        running=False,
        finishedAt=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        state="finished",
        phase="complete",
        scanProcessed=counts.get("totalBooks", ACTIVE_RUN.get("scanProcessed", 0)),
        scanTotal=counts.get("totalAvailable", counts.get("totalBooks", ACTIVE_RUN.get("scanTotal", 0))),
        candidateCount=counts.get("candidates", ACTIVE_RUN.get("candidateCount", 0)),
        currentBookId=None,
        currentTitle=None,
        lastResult={
            "ok": result.get("ok", False),
            "counts": counts,
            "updatedCount": result.get("updatedCount"),
            "plannedCount": result.get("plannedCount"),
            "metadataRefresh": result.get("metadataRefresh"),
        },
        message=(
            f"Stopped after reaching the {counts.get('candidateLimit')} candidate limit"
            if counts.get("limitReached")
            else "Run finished"
        ),
    )


def save_review_item(item: dict) -> None:
    try:
        REVIEW_QUEUE_PATH.parent.mkdir(parents=True, exist_ok=True)
        try:
            queue = json.loads(REVIEW_QUEUE_PATH.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            queue = {}
        queue[str(item["id"])] = item
        temp_path = REVIEW_QUEUE_PATH.with_suffix(".tmp")
        temp_path.write_text(json.dumps(queue, ensure_ascii=False, indent=2), encoding="utf-8")
        temp_path.replace(REVIEW_QUEUE_PATH)
    except OSError as exc:
        print(f"Could not persist LLM review item {item.get('id')}: {exc}", flush=True)


def clear_review_item(book_id: str) -> None:
    try:
        queue = json.loads(REVIEW_QUEUE_PATH.read_text(encoding="utf-8"))
        if not isinstance(queue, dict) or book_id not in queue:
            return
        del queue[book_id]
        temp_path = REVIEW_QUEUE_PATH.with_suffix(".tmp")
        temp_path.write_text(json.dumps(queue, ensure_ascii=False, indent=2), encoding="utf-8")
        temp_path.replace(REVIEW_QUEUE_PATH)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"Could not clear resolved LLM review item {book_id}: {exc}", flush=True)


def load_review_queue() -> list:
    try:
        queue = json.loads(REVIEW_QUEUE_PATH.read_text(encoding="utf-8"))
        return list(queue.values()) if isinstance(queue, dict) else []
    except (OSError, json.JSONDecodeError):
        return []


def write_review_queue_items(items: dict) -> None:
    REVIEW_QUEUE_PATH.parent.mkdir(parents=True, exist_ok=True)
    temp_path = REVIEW_QUEUE_PATH.with_suffix(".tmp")
    temp_path.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
    temp_path.replace(REVIEW_QUEUE_PATH)


def fail_run(exc: BaseException) -> None:
    result = {
        "ok": False,
        "message": str(exc) or exc.__class__.__name__,
        "errorType": exc.__class__.__name__,
        "traceback": traceback.format_exc(),
    }
    print(f"Repair run failed: {result['errorType']}: {result['message']}", flush=True)
    update_run(
        running=False,
        finishedAt=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        state="failed",
        phase="failed",
        currentBookId=None,
        currentTitle=None,
        lastResult=result,
        message=f"Run failed: {result['message']}",
    )


def parse_json_body(raw_body: bytes) -> dict:
    if not raw_body:
        return {}
    try:
        body = json.loads(raw_body.decode("utf-8"))
    except json.JSONDecodeError:
        return {}
    return body if isinstance(body, dict) else {}


def app_context() -> dict:
    return {
        "service": "grimmory-library-worker",
        "grimmory_repair": grimmory_config_status(),
        "active_run": snapshot_run(),
    }


def render_index() -> str:
    return (TEMPLATES_DIR / "index.html").read_text(encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "grimmory-metadata-fix/1.0"

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/":
            self.send_html(render_index())
            return
        if parsed.path == "/api/status":
            self.send_json(HTTPStatus.OK, app_context())
            return
        if parsed.path == "/api/consolidation/files":
            query = parse_qs(parsed.query)
            try:
                offset = max(0, int((query.get("offset") or ["0"])[0]))
                limit = min(500, max(1, int((query.get("limit") or ["100"])[0])))
            except ValueError:
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Invalid page."})
                return
            selected_ids = query.get("ids")
            if selected_ids and len(selected_ids[0].split(",")) > 100:
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Request at most 100 selected rows."})
                return
            self.send_json(HTTPStatus.OK, list_items(offset, limit, selected_ids[0].split(",") if selected_ids else None))
            return
        if parsed.path == "/api/consolidation/prepare-status":
            self.send_json(HTTPStatus.OK, {"ok": True, **prepare_status()})
            return
        if parsed.path == "/api/consolidation/import-status":
            self.send_json(HTTPStatus.OK, {"ok": True, **import_status()})
            return
        if parsed.path == "/api/grimmory/review-queue":
            self.send_json(HTTPStatus.OK, {"items": load_review_queue()})
            return
        if parsed.path == "/api/grimmory/config":
            self.send_json(HTTPStatus.OK, {"ok": True, "config": grimmory_config_status()})
            return
        if parsed.path == "/api/grimmory/scan-result":
            with STATE_LOCK:
                payload = LAST_SCAN_RESULT
            if payload is None:
                self.send_json(HTTPStatus.NOT_FOUND, {"ok": False, "message": "No completed scan is available."})
            else:
                self.send_json(HTTPStatus.OK, payload)
            return
        if parsed.path == "/static/style.css":
            self.send_file(STATIC_DIR / "style.css", "text/css; charset=utf-8")
            return
        self.send_error(HTTPStatus.NOT_FOUND)

    def do_DELETE(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/consolidation/files":
            content_length = int(self.headers.get("Content-Length", "0") or "0")
            body = parse_json_body(self.rfile.read(content_length) if content_length else b"")
            ids = body.get("ids") or []
            if not isinstance(ids, list) or any(not isinstance(value, str) for value in ids):
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Provide selected source file IDs."})
                return
            try:
                result = delete_selected(ids)
                self.send_json(HTTPStatus.OK if result["ok"] else HTTPStatus.MULTI_STATUS, result)
            except ValueError as exc:
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": str(exc)})
            return
        if parsed.path != "/api/grimmory/review-queue":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        content_length = int(self.headers.get("Content-Length", "0") or "0")
        body = parse_json_body(self.rfile.read(content_length) if content_length else b"")
        raw_ids = body.get("ids") or []
        if not isinstance(raw_ids, list):
            self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Book IDs must be provided as a list."})
            return
        ids = sorted({book_id for book_id in (safe_int(value) for value in raw_ids) if book_id is not None})
        if not ids:
            self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Select at least one review item."})
            return
        if len(ids) > 25:
            self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Delete at most 25 review items at a time."})
            return
        if not RUN_LOCK.acquire(blocking=False):
            self.send_json(HTTPStatus.CONFLICT, {"ok": False, "message": "Wait for the active scan or repair run to finish before deleting."})
            return
        try:
            try:
                queue = json.loads(REVIEW_QUEUE_PATH.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                queue = {}
            allowed_ids = {int(book_id) for book_id in queue}
            if not set(ids).issubset(allowed_ids):
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Only books currently in the review queue can be deleted."})
                return
            already_removed = [
                book_id for book_id in ids
                if queue.get(str(book_id), {}).get("deleteStatus", "").startswith("Removed from Grimmory")
            ]
            if already_removed:
                self.send_json(HTTPStatus.CONFLICT, {"ok": False, "message": "Some selected books were already removed from Grimmory; review their disk deletion status."})
                return
            try:
                result = delete_books(ids)
            except Exception as exc:
                self.send_json(HTTPStatus.BAD_GATEWAY, {"ok": False, "message": str(exc) or exc.__class__.__name__})
                return
            deleted = set(result.get("deleted") or [])
            disk_failed = set(result.get("failedFileDeletions") or [])
            for book_id in ids:
                key = str(book_id)
                if book_id in deleted and book_id not in disk_failed:
                    queue.pop(key, None)
                elif book_id in disk_failed and key in queue:
                    queue[key]["deleteStatus"] = "Removed from Grimmory; disk file deletion failed"
                elif key in queue:
                    queue[key]["deleteStatus"] = "Not removed from Grimmory"
            try:
                write_review_queue_items(queue)
            except OSError as exc:
                print(f"Could not update review queue after deletion: {exc}", flush=True)
            self.send_json(
                HTTPStatus.OK if result.get("ok") else HTTPStatus.MULTI_STATUS,
                {**result, "notDeleted": sorted(set(ids) - deleted), "remainingReviewCount": len(queue)},
            )
        finally:
            RUN_LOCK.release()

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/internal/library-tools/identity-assessment":
            configured_key = os.environ.get("LIBRARY_TOOLS_API_KEY", "")
            supplied_key = self.headers.get("X-Library-Tools-Key", "")
            if not configured_key or not hmac.compare_digest(configured_key, supplied_key):
                self.send_json(HTTPStatus.UNAUTHORIZED, {"ok": False, "message": "Unauthorized internal request."})
                return
            if int(self.headers.get("Content-Length", "0") or "0") > 65536:
                self.send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"ok": False, "message": "Internal assessment request is too large."})
                return
        content_length = int(self.headers.get("Content-Length", "0") or "0")
        raw_body = self.rfile.read(content_length) if content_length else b""
        body = parse_json_body(raw_body)

        if parsed.path == "/api/internal/library-tools/identity-assessment":
            try:
                result = identify_duplicate_group(body.get("books"))
                status = HTTPStatus.OK if result.get("ok") else HTTPStatus.BAD_GATEWAY
                self.send_json(status, result)
            except Exception:
                self.send_json(HTTPStatus.BAD_GATEWAY, {"ok": False, "message": "Book identity analysis failed."})
            return

        if parsed.path == "/api/consolidation/scan":
            try:
                result = scan_sources()
                self.send_json(HTTPStatus.OK, result)
            except Exception as exc:
                self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "message": str(exc)})
            return

        if parsed.path == "/api/consolidation/prepare":
            ids = body.get("ids") or []
            if not isinstance(ids, list) or any(not isinstance(value, str) for value in ids):
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Provide selected source file IDs."})
                return
            try:
                result = prepare_selected(ids, auto_import=bool(body.get("autoImport")))
                self.send_json(HTTPStatus.ACCEPTED, result)
            except (ValueError, RuntimeError) as exc:
                self.send_json(HTTPStatus.CONFLICT if isinstance(exc, RuntimeError) else HTTPStatus.BAD_REQUEST, {"ok": False, "message": str(exc)})
            return

        if parsed.path == "/api/consolidation/import":
            entries = body.get("books") or []
            if not isinstance(entries, list) or any(not isinstance(value, dict) or not isinstance(value.get("id"), str) for value in entries):
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Provide prepared books with their approved title and author."})
                return
            try:
                result = start_import(entries)
                self.send_json(HTTPStatus.ACCEPTED, result)
            except (ValueError, RuntimeError) as exc:
                status = HTTPStatus.CONFLICT if isinstance(exc, RuntimeError) else HTTPStatus.BAD_REQUEST
                self.send_json(status, {"ok": False, "message": str(exc)})
            return

        if parsed.path == "/api/grimmory/review-queue/exceptions":
            raw_ids = body.get("ids") or []
            exception = body.get("exception")
            if not isinstance(raw_ids, list) or not isinstance(exception, bool):
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Provide a list of review IDs and an exception boolean."})
                return
            ids = sorted({book_id for book_id in (safe_int(value) for value in raw_ids) if book_id is not None})
            if not ids or len(ids) > 25:
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Select between 1 and 25 review items."})
                return
            if not RUN_LOCK.acquire(blocking=False):
                self.send_json(HTTPStatus.CONFLICT, {"ok": False, "message": "Wait for the active scan or repair run to finish before changing exceptions."})
                return
            try:
                try:
                    queue = json.loads(REVIEW_QUEUE_PATH.read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError):
                    queue = {}
                if not isinstance(queue, dict) or not set(ids).issubset({int(book_id) for book_id in queue}):
                    self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "Only books currently in the review queue can be marked as exceptions."})
                    return
                for book_id in ids:
                    item = queue[str(book_id)]
                    if exception:
                        item["exception"] = True
                        item["exceptionMarkedAt"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
                    else:
                        item.pop("exception", None)
                        item.pop("exceptionMarkedAt", None)
                try:
                    write_review_queue_items(queue)
                except OSError as exc:
                    self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "message": f"Could not save exception settings: {exc}"})
                    return
                self.send_json(HTTPStatus.OK, {"ok": True, "exception": exception, "updatedIds": ids})
            finally:
                RUN_LOCK.release()
            return

        if parsed.path == "/api/grimmory/scan":
            if not RUN_LOCK.acquire(blocking=False):
                self.send_json(HTTPStatus.CONFLICT, {"ok": False, "message": "Another scan or repair run is already active."})
                return
            reset_run("scan", False)
            threshold = safe_float(body.get("threshold"), GRIMMORY_LOW_SCORE_THRESHOLD)
            max_updates = safe_int(body.get("maxUpdates"))
            max_updates = max_updates if max_updates and max_updates > 0 else DEFAULT_MAX_UPDATES
            threading.Thread(
                target=self._run_background_scan,
                args=(threshold, max_updates),
                daemon=True,
            ).start()
            self.send_json(HTTPStatus.ACCEPTED, {"ok": True, "message": "Grimmory scan started.", "background": True})
            return

        if parsed.path == "/api/grimmory/fix-heuristic":
            dry_run = bool(body.get("dryRun"))
            if dry_run:
                payload = run_heuristic_fix(
                    threshold=safe_float(body.get("threshold"), GRIMMORY_LOW_SCORE_THRESHOLD),
                    max_updates=safe_int(body.get("maxUpdates")),
                    dry_run=True,
                    refresh_after_update=False,
                )
                status = HTTPStatus.OK if payload.get("ok") else HTTPStatus.BAD_REQUEST
                self.send_json(status, payload)
                return
            if not RUN_LOCK.acquire(blocking=False):
                self.send_json(HTTPStatus.CONFLICT, {"ok": False, "message": "A repair run is already active."})
                return
            reset_run("heuristic", False)
            threshold = safe_float(body.get("threshold"), GRIMMORY_LOW_SCORE_THRESHOLD)
            max_updates = safe_int(body.get("maxUpdates"))
            refresh_after_update = bool(body.get("refreshAfterUpdate"))
            threading.Thread(
                target=self._run_background_heuristic,
                args=(threshold, max_updates, refresh_after_update),
                daemon=True,
            ).start()
            payload = {"ok": True, "message": "Heuristic repair started.", "background": True}
            status = HTTPStatus.ACCEPTED
            self.send_json(status, payload)
            return

        if parsed.path == "/api/grimmory/fix-llm":
            raw_ids = body.get("bookIds") or []
            book_ids = [book_id for book_id in (safe_int(item) for item in raw_ids) if book_id is not None]
            dry_run = bool(body.get("dryRun", True))
            if dry_run:
                payload = run_llm_fix(
                    threshold=safe_float(body.get("threshold"), GRIMMORY_LOW_SCORE_THRESHOLD),
                    book_ids=book_ids,
                    dry_run=True,
                    min_confidence=safe_float(body.get("minConfidence"), 0.9),
                    max_updates=safe_int(body.get("maxUpdates")),
                    refresh_after_update=False,
                )
                status = HTTPStatus.OK if payload.get("ok") else HTTPStatus.BAD_REQUEST
                self.send_json(status, payload)
                return
            if not RUN_LOCK.acquire(blocking=False):
                self.send_json(HTTPStatus.CONFLICT, {"ok": False, "message": "A repair run is already active."})
                return
            reset_run("llm", False)
            threshold = safe_float(body.get("threshold"), GRIMMORY_LOW_SCORE_THRESHOLD)
            min_confidence = safe_float(body.get("minConfidence"), 0.9)
            max_updates = safe_int(body.get("maxUpdates"))
            refresh_after_update = bool(body.get("refreshAfterUpdate"))
            threading.Thread(
                target=self._run_background_llm,
                args=(threshold, book_ids, min_confidence, max_updates, refresh_after_update),
                daemon=True,
            ).start()
            payload = {"ok": True, "message": "LLM repair started.", "background": True}
            status = HTTPStatus.ACCEPTED
            self.send_json(status, payload)
            return

        if parsed.path == "/api/grimmory/update":
            book_id = safe_int(body.get("bookId"))
            if book_id is None:
                self.send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": "bookId is required."})
                return
            payload = update_book(
                book_id,
                {
                    "title": body.get("title"),
                    "subtitle": body.get("subtitle"),
                    "authors": body.get("authors") or [],
                    "seriesName": body.get("seriesName"),
                    "seriesNumber": body.get("seriesNumber"),
                },
            )
            status = HTTPStatus.OK if payload.get("ok") else HTTPStatus.BAD_REQUEST
            self.send_json(status, payload)
            return

        self.send_error(HTTPStatus.NOT_FOUND)

    @staticmethod
    def _run_background_scan(threshold: float, max_updates: int) -> None:
        global LAST_SCAN_RESULT
        try:
            result = scan_library(threshold, max_candidates=max_updates, progress_cb=progress_callback)
            if not result.get("ok"):
                raise RuntimeError(str(result.get("message") or "Grimmory scan failed"))
            with STATE_LOCK:
                LAST_SCAN_RESULT = result
            finish_run(result)
        except Exception as exc:
            fail_run(exc)
        finally:
            RUN_LOCK.release()

    @staticmethod
    def _run_background_heuristic(threshold: float, max_updates: int | None, refresh_after_update: bool) -> None:
        try:
            result = run_heuristic_fix(
                threshold=threshold,
                max_updates=max_updates,
                dry_run=False,
                refresh_after_update=refresh_after_update,
                progress_cb=progress_callback,
            )
            finish_run(result)
        except Exception as exc:
            fail_run(exc)
        finally:
            RUN_LOCK.release()

    @staticmethod
    def _run_background_llm(
        threshold: float,
        book_ids: list[int],
        min_confidence: float,
        max_updates: int | None,
        refresh_after_update: bool,
    ) -> None:
        try:
            result = run_llm_fix(
                threshold=threshold,
                book_ids=book_ids,
                dry_run=False,
                min_confidence=min_confidence,
                max_updates=max_updates,
                refresh_after_update=refresh_after_update,
                progress_cb=progress_callback,
            )
            finish_run(result)
        except Exception as exc:
            fail_run(exc)
        finally:
            RUN_LOCK.release()

    def log_message(self, format: str, *args) -> None:
        return

    def send_html(self, body: str) -> None:
        encoded = body.encode("utf-8")
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def send_json(self, status: HTTPStatus, payload: dict) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def send_file(self, path: Path, content_type: str) -> None:
        if not path.exists():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        encoded = path.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", APP_PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
