import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import consolidation as c
import grimmory_repair as repair


class LegacyInferenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.sources = patch.dict(c.SOURCE_ROOTS, {"epub": ("epub", self.root)}, clear=True)
        self.sources.start()
        self.item = {"source": "epub", "name": "An Elf to Myself - Emy Calirel.epub",
                     "relativePath": "Emy Calirel/An Elf to Myself (26454)/An Elf to Myself - Emy Calirel.epub"}

    def tearDown(self):
        self.sources.stop()
        self.temp.cleanup()

    def proposal(self, **kwargs):
        return {"title": "An Elf to Myself", "authors": ["Emy Calirel"], "confidence": .95,
                "should_update": True, "rationale": "Filename and author folder agree.", **kwargs}

    def create_source(self):
        path = self.root / self.item["relativePath"]
        path.parent.mkdir(parents=True)
        path.write_bytes(b"fixture book")
        return path

    def test_missing_source_still_sends_full_path_and_returns_reviewable_identity(self):
        with patch.object(c, "_llm_decide", return_value=self.proposal()) as llm:
            result = c._prepare_one("fixture", self.item)
        context = llm.call_args.args[0]
        self.assertEqual(str(self.root / self.item["relativePath"]), context["sourcePath"])
        self.assertEqual("An Elf to Myself", context["pathHints"]["bookFolderWithoutCalibreId"])
        self.assertEqual(["An Elf to Myself", "Emy Calirel"], context["pathHints"]["filenameParts"])
        self.assertEqual("An Elf to Myself", result["title"])
        self.assertEqual(["Emy Calirel"], result["authors"])
        self.assertFalse(result["sourceAvailable"])
        self.assertIsNone(result["sourceHash"])
        self.assertLess(result["confidence"], .5)
        self.assertFalse(result["shouldUpdate"])

    def test_missing_book_can_still_use_its_calibre_sidecar(self):
        folder = (self.root / self.item["relativePath"]).parent
        folder.mkdir(parents=True)
        (folder / "metadata.opf").write_text('<package xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>An Elf to Myself</dc:title><dc:creator>Emy Calirel</dc:creator></package>')
        with patch.object(c, "_llm_decide", return_value=self.proposal()) as llm:
            result = c._prepare_one("fixture", self.item)
        self.assertEqual("Emy Calirel", llm.call_args.args[0]["authors"])
        self.assertEqual("An Elf to Myself", result["_currentMetadata"]["title"])
        self.assertFalse(result["sourceAvailable"])
        self.assertLess(result["confidence"], .5)

    def test_sidecar_symlinks_are_not_followed(self):
        folder = (self.root / self.item["relativePath"]).parent
        folder.mkdir(parents=True)
        outside = self.root / "outside.xml"
        outside.write_text('<package/>')
        (folder / "metadata.opf").symlink_to(outside)
        self.assertEqual({}, c._read_sidecar_metadata(folder / self.item["name"]))

    def test_unreadable_metadata_does_not_leave_clear_path_identity_blank(self):
        self.create_source()
        with patch.object(c, "_read_file_metadata", side_effect=RuntimeError("bad metadata")), \
             patch.object(c, "_sample_source_text", return_value=""), \
             patch.object(c, "_llm_decide", return_value=self.proposal()):
            result = c._prepare_one("fixture", self.item)
        self.assertEqual("An Elf to Myself", result["title"])
        self.assertLess(result["confidence"], .5)
        self.assertIsNotNone(result["sourceHash"])
        self.assertIn("bad metadata", result["rationale"])

    def test_sidecar_only_evidence_does_not_allow_automatic_import(self):
        self.create_source()
        with patch.object(c, "_read_file_metadata", side_effect=RuntimeError("unreadable")), \
             patch.object(c, "_read_sidecar_metadata", return_value={"title": "An Elf to Myself", "authors": "Emy Calirel"}), \
             patch.object(c, "_sample_source_text", return_value=""), \
             patch.object(c, "_llm_decide", return_value=self.proposal()):
            result = c._prepare_one("fixture", self.item)
        self.assertLess(result["confidence"], .5)
        self.assertFalse(result["shouldUpdate"])

    def test_readable_text_keeps_high_confidence_title_and_source_fingerprint(self):
        self.create_source()
        with patch.object(c, "_read_file_metadata", return_value={}), \
             patch.object(c, "_sample_source_text", return_value="An Elf to Myself by Emy Calirel"), \
             patch.object(c, "_llm_decide", return_value=self.proposal(title="An Elf to Myself: A Novel")):
            result = c._prepare_one("fixture", self.item)
        self.assertEqual("An Elf to Myself: A Novel", result["title"])
        self.assertEqual(.95, result["confidence"])
        self.assertTrue(result["sampleAvailable"])
        self.assertTrue(result["sourceAvailable"])
        self.assertEqual(64, len(result["sourceHash"]))

    def test_changed_source_cannot_be_imported_using_a_sample_from_earlier_bytes(self):
        path = self.create_source()
        def change_source(_):
            path.write_bytes(b"different book bytes")
            return self.proposal()
        with patch.object(c, "_read_file_metadata", return_value={}), \
             patch.object(c, "_sample_source_text", return_value="Book opening"), \
             patch.object(c, "_llm_decide", side_effect=change_source):
            result = c._prepare_one("fixture", self.item)
        self.assertIsNone(result["sourceHash"])
        self.assertLess(result["confidence"], .5)
        self.assertFalse(result["shouldUpdate"])

    def test_manual_approval_cannot_stage_a_missing_file(self):
        item = {**self.item, "prepared": True, "proposal": {"sourceAvailable": False}}
        target = self.root / "target"
        with patch.dict(c.ITEMS, {"fixture": item}, clear=True), patch.object(c, "TARGET_ROOT", target):
            result = c.import_approved([{"id": "fixture", "title": "An Elf to Myself", "authors": ["Emy Calirel"]}])
        self.assertFalse(result["ok"])
        self.assertEqual(1, len(result["failed"]))
        self.assertFalse(target.exists())

    def test_azw3_text_is_sampled_using_a_temporary_conversion(self):
        def convert(args, **kwargs):
            Path(args[2]).write_text("The Wizard Merlin\nEmrys Apollo\nOpening text")
            return type("Result", (), {"returncode": 0})()
        with patch.object(c.subprocess, "run", side_effect=convert) as run:
            text = c._sample_source_text(Path("The Wizard Merlin.azw3"))
        self.assertIn("Emrys Apollo", text)
        self.assertEqual("ebook-convert", run.call_args.args[0][0])

    def test_llm_request_contains_full_path_and_inference_instructions(self):
        captured = {}
        class Response:
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def read(self): return json.dumps({"choices": [{"message": {"content": json.dumps(self_proposal)}}]}).encode()
        self_proposal = self.proposal()
        def send(request, **kwargs):
            captured.update(json.loads(request.data))
            return Response()
        with patch.object(repair.request, "urlopen", side_effect=send), \
             patch.object(repair, "LLM_BASE_URL", "https://llm.fixture.invalid/v1"):
            repair._llm_decide({"id": "fixture", "fileName": self.item["name"],
                                "sourcePath": "/sources/epub/" + self.item["relativePath"],
                                "pathHints": {"parentFolders": ["Emy Calirel"]}})
        user = json.loads(captured["messages"][1]["content"])
        self.assertIn("26454", user["current"]["sourcePath"])
        self.assertEqual(["Emy Calirel"], user["current"]["pathHints"]["parentFolders"])
        self.assertIn("still propose", captured["messages"][0]["content"])


if __name__ == "__main__":
    unittest.main()
