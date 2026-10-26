#!/usr/bin/env python3
"""Local-only contract tests; no SAP, Docker, credentials or external requests."""
import contextlib
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest


SCRIPT = Path(__file__).with_name("healthcheck.sh")
TOKEN = 'synthetic-test-token-0123456789-"\\$`'


@contextlib.contextmanager
def server(cert=None, key=None, status=200, delay=0, response_body=None):
    requests = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            requests.append((self.command, self.path, self.headers.get("Authorization")))
            time.sleep(delay)
            actual_status = status if self.headers.get("Authorization") == f"Bearer {TOKEN}" else 401
            body = response_body or b'{"status":"ok"}'
            try:
                self.send_response(actual_status)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Location", "/unexpected-redirect")
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                pass

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    httpd.daemon_threads = True
    if cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield httpd.server_port, requests
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=2)


class HealthcheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="sap-healthcheck-test-")
        cls.root = Path(cls.tmp.name)
        cls.cert, cls.key = cls.root / "cert.pem", cls.root / "key.pem"
        subprocess.run([
            shutil.which("openssl"), "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", str(cls.key), "-out", str(cls.cert), "-days", "1",
            "-subj", "/CN=deliberately-not-loopback.invalid",
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_probe(self, port, token=TOKEN, mode="student-provisioning", extra=None, shell="/bin/sh"):
        # Build a minimal synthetic environment; never inherit production secrets.
        env = {"PATH": os.defpath, "LC_ALL": "C", "PORT": str(port), "RFC_GUARD_MODE": mode}
        if token is not None:
            env["RFC_GUARD_API_TOKEN"] = token
        env.update(extra or {})
        result = subprocess.run([shell, str(SCRIPT)], env=env, capture_output=True, timeout=7)
        self.assertEqual(result.stdout, b"")
        self.assertEqual(result.stderr, b"")
        return result

    def test_http_env_auth_path_and_single_request(self):
        with server() as (port, requests):
            self.assertEqual(self.run_probe(port).returncode, 0)
            self.assertEqual(requests, [("GET", "/v1/health", f"Bearer {TOKEN}")])

    def test_https_legacy_self_signed_and_hostname_mismatch(self):
        with server(self.cert, self.key) as (port, requests):
            self.assertEqual(self.run_probe(port, mode="user-provisioning").returncode, 0)
            self.assertEqual(len(requests), 1)

    def test_wrong_protocol_fails(self):
        with server() as (port, requests):
            self.assertNotEqual(self.run_probe(port, mode="user-provisioning").returncode, 0)
            self.assertEqual(requests, [])

    def test_file_auth(self):
        token_file = self.root / "token"
        token_file.write_text(TOKEN + "\n")
        token_file.chmod(0o600)
        with server() as (port, _):
            self.assertEqual(self.run_probe(port, token=None, extra={
                "RFC_GUARD_API_TOKEN_FILE": str(token_file),
            }).returncode, 0)

    def test_env_wins_over_file(self):
        token_file = self.root / "other-token"
        token_file.write_text("wrong-token\n")
        with server() as (port, _):
            self.assertEqual(self.run_probe(port, extra={
                "RFC_GUARD_API_TOKEN_FILE": str(token_file),
            }).returncode, 0)

    def test_blank_env_uses_file(self):
        token_file = self.root / "blank-env-token"
        token_file.write_text(TOKEN)
        with server() as (port, _):
            self.assertEqual(self.run_probe(port, token="   ", extra={
                "RFC_GUARD_API_TOKEN_FILE": str(token_file),
            }).returncode, 0)

    def test_missing_empty_and_injected_tokens_never_send(self):
        for value in (None, "", "   ", "wrong\r\nX-Injected: true", "bad\ttoken"):
            with self.subTest(token_kind=repr(value)), server() as (port, requests):
                self.assertNotEqual(self.run_probe(port, token=value).returncode, 0)
                self.assertEqual(requests, [])

    def test_missing_or_multiline_token_file_never_sends(self):
        multiline = self.root / "multiline"
        multiline.write_text(TOKEN + "\nsecond-line\n")
        for filename in (self.root / "missing", multiline):
            with self.subTest(file_kind=filename.name), server() as (port, requests):
                self.assertNotEqual(self.run_probe(port, token=None, extra={
                    "RFC_GUARD_API_TOKEN_FILE": str(filename),
                }).returncode, 0)
                self.assertEqual(requests, [])

    def test_bad_auth_fails_once(self):
        with server() as (port, requests):
            self.assertNotEqual(self.run_probe(port, token="wrong").returncode, 0)
            self.assertEqual(len(requests), 1)

    def test_only_200_and_no_redirect_or_retry(self):
        for status in (204, 301, 401, 403, 429, 500, 503):
            with self.subTest(status=status), server(status=status) as (port, requests):
                self.assertNotEqual(self.run_probe(port).returncode, 0)
                self.assertEqual(len(requests), 1)

    def test_response_body_ignored_like_java_probe(self):
        with server(response_body=b"synthetic opaque response") as (port, _):
            self.assertEqual(self.run_probe(port).returncode, 0)

    def test_total_timeout_four_seconds(self):
        with server(delay=5.0) as (port, requests):
            started = time.monotonic()
            self.assertNotEqual(self.run_probe(port).returncode, 0)
            duration = time.monotonic() - started
            self.assertGreaterEqual(duration, 3.5)
            self.assertLess(duration, 4.9)
            self.assertEqual(len(requests), 1)

    def test_invalid_port_never_sends(self):
        for port in ("", "0", "65536", "-1", "8080/other", "1@elsewhere", "999999999999999999999999"):
            with self.subTest(port=port):
                self.assertNotEqual(self.run_probe(port).returncode, 0)

    def test_connection_refused_fails(self):
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            self.assertNotEqual(self.run_probe(reserved.getsockname()[1]).returncode, 0)

    def test_curlrc_and_proxy_ignored(self):
        home = self.root / "synthetic-home"
        home.mkdir(exist_ok=True)
        (home / ".curlrc").write_text("url = http://127.0.0.1:1/should-not-be-used\n")
        with server() as (port, requests):
            self.assertEqual(self.run_probe(port, extra={
                "CURL_HOME": str(home), "http_proxy": "http://127.0.0.1:1",
                "https_proxy": "http://127.0.0.1:1", "ALL_PROXY": "http://127.0.0.1:1",
            }).returncode, 0)
            self.assertEqual(len(requests), 1)

    def test_token_not_in_curl_argv_or_environment(self):
        wrapper_dir = self.root / "wrapper"
        wrapper_dir.mkdir(exist_ok=True)
        capture = self.root / "curl-arguments.json"
        wrapper = wrapper_dir / "curl"
        # Capture argv/env only, not the authenticated stdin stream.
        wrapper.write_text(
            "#!/usr/bin/python3\nimport json, os, sys\n"
            f"open({str(capture)!r}, 'w').write(json.dumps([sys.argv, dict(os.environ)]))\n"
            f"os.execv({shutil.which('curl')!r}, ['curl'] + sys.argv[1:])\n"
        )
        wrapper.chmod(0o700)
        with server() as (port, _):
            self.assertEqual(self.run_probe(port, extra={
                "PATH": str(wrapper_dir) + os.pathsep + os.defpath,
            }).returncode, 0)
        argv, environment = json.loads(capture.read_text())
        self.assertTrue(all(TOKEN not in value for value in argv))
        self.assertNotIn("RFC_GUARD_API_TOKEN", environment)
        self.assertTrue(all(TOKEN not in value for value in environment.values()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
