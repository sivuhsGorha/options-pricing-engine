"""Tests for fetch_real_api_data.py (standard library only: python -m unittest discover -s scripts)."""
import csv
import importlib.util
import io
import os
import pathlib
import tempfile
import unittest
from contextlib import redirect_stdout

ROOT = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("fetch_real_api_data", ROOT / "fetch_real_api_data.py")
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)

SENTINEL = "SENTINEL: previous good data\n"


class FetchRealApiDataTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.output = os.path.join(self.tmp.name, "market_data.csv")
        with open(self.output, "w", newline="") as f:
            f.write(SENTINEL)

    def run_main(self, argv, env=None, http=None):
        out = io.StringIO()
        with redirect_stdout(out):
            # --dotenv points at a path that does not exist so a developer's real .env can never leak into a test.
            code = fetch.main(["--output", self.output, "--dotenv", os.path.join(self.tmp.name, "no.env")] + argv,
                              env=env or {}, http=http)
        return code, out.getvalue()

    # ---- never present made-up data as live ----

    def test_live_mode_with_no_quote_fails_and_leaves_the_existing_csv_alone(self):
        code, _ = self.run_main([], env={})
        self.assertEqual(2, code)
        with open(self.output) as f:
            self.assertEqual(SENTINEL, f.read())

    def test_every_provider_failing_fails_and_leaves_the_existing_csv_alone(self):
        def down(url, headers, timeout):
            raise OSError("connection refused")

        code, _ = self.run_main([], env={"FINNHUB_KEY": "fh", "POLYGON_API_KEY": "pg"}, http=down)
        self.assertEqual(2, code)
        with open(self.output) as f:
            self.assertEqual(SENTINEL, f.read())

    def test_synthetic_data_only_when_explicitly_requested(self):
        code, out = self.run_main(["--synthetic", "--spot", "500"])
        self.assertEqual(0, code)
        self.assertIn("SYNTHETIC", out.upper())
        with open(self.output, newline="") as f:
            rows = list(csv.reader(f))
        self.assertEqual(["timestamp", "instrument_id", "type", "strike", "bid_price", "bid_size", "ask_price", "ask_size"], rows[0])
        self.assertGreater(len(rows), 10)
        timestamps = [int(r[0]) for r in rows[1:]]
        self.assertEqual(sorted(set(timestamps)), timestamps, "timestamps must be strictly increasing (the replay uses them as a sequence)")

    def test_synthetic_mode_rejects_a_nonsense_spot(self):
        for bad in ("0", "-5", "nan", "inf"):
            code, _ = self.run_main(["--synthetic", "--spot", bad])
            self.assertEqual(2, code, bad)
            with open(self.output) as f:
                self.assertEqual(SENTINEL, f.read())

    # ---- sources and keys ----

    def test_only_configured_providers_are_called_and_none_sends_the_text_None(self):
        calls = []

        def http(url, headers, timeout):
            calls.append((url, dict(headers)))
            return {"c": 501.25}

        code, _ = self.run_main([], env={"FINNHUB_KEY": "fh-key"}, http=http)
        self.assertEqual(0, code)
        self.assertEqual(1, len(calls))
        url, headers = calls[0]
        self.assertIn("finnhub.io", url)
        self.assertEqual("fh-key", headers["X-Finnhub-Token"])
        self.assertTrue(all("None" not in v for v in headers.values()))
        self.assertNotIn("fh-key", url)

    def test_falls_through_to_the_next_provider_and_never_prints_a_key(self):
        def http(url, headers, timeout):
            if "finnhub" in url:
                raise OSError("boom " + url + " token=SECRETFH")
            return {"results": [{"c": 498.5}]}

        env = {"FINNHUB_KEY": "SECRETFH", "POLYGON_API_KEY": "SECRETPG"}
        code, out = self.run_main([], env=env, http=http)
        self.assertEqual(0, code)
        self.assertNotIn("SECRETFH", out)
        self.assertNotIn("SECRETPG", out)
        with open(self.output, newline="") as f:
            self.assertGreater(len(list(csv.reader(f))), 10)

    def test_a_provider_returning_a_nonsense_price_is_skipped(self):
        def http(url, headers, timeout):
            if "finnhub" in url:
                return {"c": 0}
            return {"results": [{"c": 450.0}]}

        code, _ = self.run_main([], env={"FINNHUB_KEY": "a", "POLYGON_API_KEY": "b"}, http=http)
        self.assertEqual(0, code)

    def test_end_of_day_close_is_called_out_as_not_live(self):
        def http(url, headers, timeout):
            return {"results": [{"c": 450.0}]}

        _, out = self.run_main([], env={"POLYGON_API_KEY": "b"}, http=http)
        self.assertIn("NOT LIVE", out.upper())

    def test_requests_have_a_timeout(self):
        seen = []

        def http(url, headers, timeout):
            seen.append(timeout)
            return {"c": 500.0}

        self.run_main([], env={"FINNHUB_KEY": "a"}, http=http)
        self.assertTrue(seen and all(t is not None and 0 < t <= 10 for t in seen), seen)

    # ---- the file ----

    def test_a_failure_while_writing_leaves_the_previous_file_and_no_temp_files(self):
        original = fetch.build_rows

        def explode(*args, **kwargs):
            def gen():
                yield ["header"]
                raise RuntimeError("disk full")
            return gen()

        fetch.build_rows = explode
        try:
            with self.assertRaises(RuntimeError):
                with redirect_stdout(io.StringIO()):
                    fetch.main(["--output", self.output, "--synthetic", "--spot", "500"], env={})
        finally:
            fetch.build_rows = original
        with open(self.output) as f:
            self.assertEqual(SENTINEL, f.read())
        leftovers = [n for n in os.listdir(self.tmp.name) if n != "market_data.csv"]
        self.assertEqual([], leftovers)

    def test_dotenv_values_are_used_but_real_environment_wins(self):
        env_file = os.path.join(self.tmp.name, ".env")
        with open(env_file, "w") as f:
            f.write("# comment\nFINNHUB_KEY=from-file\nPOLYGON_API_KEY=file-poly\n\nBAD LINE\n")
        merged = fetch.load_keys(env={"POLYGON_API_KEY": "from-env"}, dotenv_path=env_file)
        self.assertEqual("from-file", merged["FINNHUB_KEY"])
        self.assertEqual("from-env", merged["POLYGON_API_KEY"])


if __name__ == "__main__":
    unittest.main()
