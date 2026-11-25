import argparse
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import discover_more_items as dm  # noqa: E402


def doc(ident, title, date, cols=("opensource_movies",), **extra):
    return {"identifier": ident, "title": title, "addeddate": date, "collection": list(cols), **extra}


class DiscoverMoreItemsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        vault = [{"identifier": f"v{i}", "format_guess": "VHS", "kind_guess": "movie_or_unknown",
                  "added_to_archive": f"2020-01-0{i}T00:00:00Z"} for i in range(1, 6)]
        (self.tmp / "vault.jsonl").write_text("".join(json.dumps(r) + "\n" for r in vault))
        self.uploaders = {"v1": "tapes@example.com", "v2": "tapes@example.com", "v3": "tapes@example.com",
                          "v4": "once@example.com", "v5": None}
        self.queries = []

    def args(self, **kw):
        base = dict(known=None, state=None, uploader_cache=str(self.tmp / "cache" / "up.json"),
                    vault_items=str(self.tmp / "vault.jsonl"), catalog_items=None,
                    output_dir=str(self.tmp / "out"), ways=["uploaders", "keywords", "collections"],
                    rows=2, max_pages=100, pages_per_source=2, uploader_lookups=10, delay=0, lookup_delay=0)
        base.update(kw)
        return argparse.Namespace(**base)

    def fake_search(self, query, page, rows):
        self.queries.append(query)
        if 'uploader:"tapes@example.com"' in query:
            if "addeddate:[2021-02-01T00:00:00Z TO *]" in query:
                return [doc("u2", "Big (1988) VHS Rip", "2021-02-01T00:00:00Z")]
            return [doc("u1", "Willow (1988) [VHS]", "2021-01-01T00:00:00Z"),
                    doc("u2", "Big (1988) VHS Rip", "2021-02-01T00:00:00Z")]
        if '"vhs rip"' in query and "addeddate" not in query:
            return [doc("k1", "Some Tape", "2019-01-01T00:00:00Z", description="Ripped from my vhs rip deck"),
                    doc("x1", "Penthouse Tape", "2019-01-02T00:00:00Z"),
                    doc("v2", "Already in the Vault", "2019-01-03T00:00:00Z")]
        if "collection:classic_tv_commercials_emperor" in query and "addeddate" not in query:
            return [doc("c1", "1985 Pepsi", "2010-01-01T00:00:00Z", cols=("classic_tv_commercials_emperor",))]
        return []

    def read_out(self):
        out = self.tmp / "out"
        rows = [json.loads(x) for x in (out / "more-items.jsonl").read_text().splitlines()]
        return {r["identifier"]: r for r in rows}, json.loads((out / "more-sources-state.json").read_text())

    def test_finds_items_three_ways_and_keeps_emails_private(self):
        summary = dm.run(self.args(), self.fake_search, self.uploaders.get)
        rows, state = self.read_out()
        self.assertEqual(set(rows), {"u1", "u2", "k1", "c1"})       # adult and Vault pages left out
        self.assertTrue(rows["u1"]["found_by_uploader"])
        self.assertEqual(rows["u1"]["format_guess"], "VHS")
        self.assertEqual(rows["k1"]["format_guess"], "VHS")          # learned from the description
        self.assertEqual(rows["k1"]["format_from"], "description")
        self.assertEqual(rows["c1"]["kind_guess"], "commercials")
        self.assertEqual(summary["uploaders_followed"], 1)          # once@ has too few Vault pages
        published = (self.tmp / "out" / "more-items.jsonl").read_text() + json.dumps(state) + json.dumps(summary)
        self.assertNotIn("@example.com", published)
        self.assertIn("tapes@example.com", (self.tmp / "cache" / "up.json").read_text())
        code = dm.uploader_code("tapes@example.com")
        self.assertEqual(state["sources"][f"uploader:{code}"]["marker"], "2021-02-01T00:00:00Z")

    def test_next_run_continues_and_does_not_relookup(self):
        dm.run(self.args(), self.fake_search, self.uploaders.get)
        out = self.tmp / "out"
        (self.tmp / "known.jsonl").write_text((out / "more-items.jsonl").read_text())
        (self.tmp / "state.json").write_text((out / "more-sources-state.json").read_text())
        self.queries.clear()

        def no_lookups(_ident):
            raise AssertionError("looked up again")

        summary = dm.run(self.args(known=str(self.tmp / "known.jsonl"), state=str(self.tmp / "state.json")),
                         self.fake_search, no_lookups)
        rows, _ = self.read_out()
        self.assertEqual(summary["new_this_run"], {})
        self.assertEqual(len(rows), 4)
        self.assertTrue(any("addeddate:[2021-02-01T00:00:00Z TO *]" in q for q in self.queries))

    def test_same_second_page_steps_forward(self):
        src = dm.Source("k", "keyword", "keyword x", "x", {})
        src.advance([doc("a", "A", "2020-01-01T00:00:00Z"), doc("b", "B", "2020-01-01T00:00:00Z")], rows=2)
        src.advance([doc("c", "C", "2020-01-01T00:00:00Z"), doc("d", "D", "2020-01-01T00:00:00Z")], rows=2)
        query, page = src.next_query()
        self.assertEqual(page, 2)
        self.assertIn("addeddate:[2020-01-01T00:00:00Z TO *]", query)

    def test_unwanted(self):
        self.assertTrue(dm.is_unwanted(doc("a", "Fine", "x", cols=("adultvhs",))))
        self.assertTrue(dm.is_unwanted(doc("a", "Playboy Video Calendar", "x")))
        self.assertTrue(dm.is_unwanted(doc("a", "Fine", "x", **{"access-restricted-item": "true"})))
        self.assertFalse(dm.is_unwanted(doc("a", "Sexton Blake (1972) VHS", "x")))


if __name__ == "__main__":
    unittest.main()
