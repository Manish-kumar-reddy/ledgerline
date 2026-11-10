#!/usr/bin/env python3
"""Tests for scripts/seed-demo.py that need no backend and no database.

    python3 scripts/test_seed_demo.py
"""
import http.server
import importlib.util
import sys
import threading
import unittest
from pathlib import Path

sys.dont_write_bytecode = True      # leave no __pycache__ in the repository
_spec = importlib.util.spec_from_file_location("seed_demo", Path(__file__).with_name("seed-demo.py"))
seed = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(seed)


class _Recorder(http.server.BaseHTTPRequestHandler):
    """Records every request; the 'redirector' answers 302 to the 'target' server."""
    seen = []
    location = None

    def _handle(self):
        type(self).seen.append((self.command, self.path, self.headers.get("Authorization")))
        if type(self).location:
            self.send_response(302)
            self.send_header("Location", type(self).location + self.path)
            self.end_headers()
        else:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b"{}")

    do_GET = do_POST = _handle

    def log_message(self, *args):
        pass


def _server(handler):
    server = http.server.HTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


class RedirectTest(unittest.TestCase):
    def test_a_redirect_is_refused_and_never_followed(self):
        target_handler = type("Target", (_Recorder,), {"seen": [], "location": None})
        target = _server(target_handler)
        redirector_handler = type("Redirector", (_Recorder,),
                                  {"seen": [], "location": f"http://127.0.0.1:{target.server_port}"})
        redirector = _server(redirector_handler)
        try:
            api = f"http://127.0.0.1:{redirector.server_port}"
            with self.assertRaises(seed.SeedError) as raised:
                seed.call(api, "GET", "/api/auth/me", token="secret-token")
            self.assertIn("redirect (HTTP 302)", str(raised.exception))
            self.assertEqual(len(redirector_handler.seen), 1)
            self.assertEqual(redirector_handler.seen[0][2], "Bearer secret-token")
            self.assertEqual(target_handler.seen, [], "the redirect target must never be contacted")

            with self.assertRaises(seed.SeedError):
                seed.call(api, "POST", "/api/auth/login", {"email": "a@b.c", "password": "x"})
            self.assertEqual(target_handler.seen, [])
        finally:
            redirector.shutdown()
            target.shutdown()


def _env(**overrides):
    base = {
        "LEDGERLINE_API_BASE_URL": "https://api.example.test",
        "DB_URL": "jdbc:postgresql://ep-example.neon.tech/ledgerline?sslmode=require",
        "DB_OWNER_USERNAME": "ledgerline_owner", "DB_OWNER_PASSWORD": "dummy-not-a-secret",
        "DEMO_HOLDER_EMAIL": "holder@example.test", "DEMO_HOLDER_PASSWORD": "holder password 1",
        "DEMO_ADMIN_EMAIL": "admin@example.test", "DEMO_ADMIN_PASSWORD": "admin password 22",
        "DEMO_AUDITOR_EMAIL": "auditor@example.test", "DEMO_AUDITOR_PASSWORD": "auditor password 3",
    }
    base.update(overrides)
    return base


class ConfigTest(unittest.TestCase):
    def assertRefused(self, fragment, **overrides):
        with self.assertRaises(seed.SeedError) as raised:
            seed.config(_env(**overrides))
        self.assertIn(fragment, str(raised.exception))

    def test_a_valid_production_configuration_is_accepted(self):
        cfg = seed.config(_env())
        self.assertEqual(cfg["db"]["sslmode"], "require")
        self.assertEqual([u["role"] for u in cfg["users"]], ["ACCOUNT_HOLDER", "ADMIN", "AUDITOR"])

    def test_unsafe_targets_are_refused(self):
        self.assertRefused("https URL", LEDGERLINE_API_BASE_URL="http://api.example.test")
        self.assertRefused("https URL", LEDGERLINE_API_BASE_URL="https://localhost")
        self.assertRefused("no path", LEDGERLINE_API_BASE_URL="https://api.example.test/api")
        self.assertRefused("local database", DB_URL="jdbc:postgresql://localhost/ledgerline?sslmode=require")
        self.assertRefused("local database", DB_URL="jdbc:postgresql://ep-example.neon.tech/ledgerline_db?sslmode=require")
        self.assertRefused("sslmode", DB_URL="jdbc:postgresql://ep-example.neon.tech/ledgerline?sslmode=disable")
        self.assertRefused("sslmode", DB_URL="jdbc:postgresql://ep-example.neon.tech/ledgerline")

    def test_test_mode_is_limited_to_the_local_test_database(self):
        self.assertRefused("only allows the database ledgerline_test_db", LEDGERLINE_SEED_ALLOW_TEST_TARGET="1",
                           LEDGERLINE_API_BASE_URL="http://localhost:8080",
                           DB_URL="jdbc:postgresql://localhost/ledgerline_db")
        self.assertRefused("only allows a local API", LEDGERLINE_SEED_ALLOW_TEST_TARGET="1",
                           DB_URL="jdbc:postgresql://localhost/ledgerline_test_db")

    def test_credentials_are_validated_before_anything_changes(self):
        self.assertRefused("missing: DEMO_ADMIN_PASSWORD", DEMO_ADMIN_PASSWORD="")
        self.assertRefused("12 to 72 UTF-8 bytes", DEMO_HOLDER_PASSWORD="short")
        self.assertRefused("must be different", DEMO_ADMIN_EMAIL="holder@example.test")
        self.assertRefused("passwords must be different", DEMO_AUDITOR_PASSWORD="holder password 1")
        self.assertRefused("DEMO_INITIAL_DEPOSIT", DEMO_INITIAL_DEPOSIT="1.001")


if __name__ == "__main__":
    unittest.main(verbosity=2)
