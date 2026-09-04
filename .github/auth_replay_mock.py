#!/usr/bin/env python3
import base64
import json
import secrets
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
ACCOUNT = f"http://{HOST}:18080"
ID = f"http://{HOST}:18081"
API = f"http://{HOST}:18082"
LOGIN = f"http://{HOST}:18083"
AUTH = f"http://{HOST}:18084"
TOUCH = f"http://{HOST}:18085"

fresh = {
    "challenge": "fresh_challenge_" + secrets.token_urlsafe(24),
    "state": "fresh_state_" + secrets.token_urlsafe(18),
    "oid": "fresh_oid_" + secrets.token_urlsafe(14),
    "anonymous": "fresh_anonymous_" + secrets.token_urlsafe(22),
    "auth_token": "fresh_auth_token_" + secrets.token_urlsafe(22),
    "sid": "fresh_sid_" + secrets.token_urlsafe(18),
    "payload": "fresh_payload_" + secrets.token_urlsafe(24),
    "session": "fresh_session_" + secrets.token_urlsafe(24),
}
runtime = {"device_id": "", "connected": False, "silent_ok": False}


def enc(value):
    return urllib.parse.quote(str(value), safe="")


def b64json(value):
    raw = json.dumps(value, separators=(",", ":")).encode()
    return base64.b64encode(raw).decode()


def fresh_id_url():
    action = b64json({"name": "mail_auth", "params": {"mail_auth_type": "auth_login_page"}})
    settings = b64json({"service_groups": {"oid": fresh["oid"]}})
    return (
        f"{ID}/auth?action={enc(action)}"
        f"&app_id=7539952"
        f"&app_settings={enc(settings)}"
        f"&code_challenge={enc(fresh['challenge'])}"
        f"&code_challenge_method=S256"
        f"&redirect_state={enc(fresh['state'])}"
        f"&redirect_uri={enc(TOUCH + '/messages')}"
        f"&response_type=silent_token"
    )


def expected_to_decoded():
    return (
        f"{AUTH}/api/v1/vkid_auth/silent/grey_login"
        f"?state={fresh['state']}"
        f"&from={enc(enc(fresh_id_url()))}"
        f"&email={enc('user@example.test')}"
    )


def next_url():
    return (
        f"{AUTH}/api/v1/vkid_auth/silent/grey_login"
        f"?state={fresh['state']}"
        f"&from={enc(fresh_id_url())}"
        f"&email={enc('user@example.test')}"
        f"&payload={enc(fresh['payload'])}"
    )


def form(handler):
    size = int(handler.headers.get("Content-Length", "0") or "0")
    raw = handler.rfile.read(size).decode("utf-8") if size else ""
    parsed = urllib.parse.parse_qs(raw, keep_blank_values=True)
    return {key: values[-1] if values else "" for key, values in parsed.items()}


class Handler(BaseHTTPRequestHandler):
    server_version = "WebResearchAuthMock/1.0"

    def log_message(self, fmt, *args):
        print(f"[{self.server.server_port}] " + fmt % args, flush=True)

    def json_response(self, code, value, headers=None):
        data = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for key, val in (headers or {}).items():
            self.send_header(key, val)
        self.end_headers()
        self.wfile.write(data)

    def text_response(self, code, text, headers=None):
        data = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        for key, val in (headers or {}).items():
            self.send_header(key, val)
        self.end_headers()
        self.wfile.write(data)

    def reject(self, message):
        print("REJECT:", message, flush=True)
        self.json_response(409, {"error": message})

    def do_GET(self):
        port = self.server.server_port
        parsed = urllib.parse.urlparse(self.path)

        if parsed.path == "/health":
            self.text_response(200, "ok")
            return

        if port == 18080 and parsed.path == "/login":
            # Runtime route must contain values different from the captured fixture.
            self.text_response(200, "<html><script>location.href=" + json.dumps(fresh_id_url()) + ";</script></html>")
            return

        if port == 18081 and parsed.path == "/auth":
            query = dict(urllib.parse.parse_qsl(parsed.query, keep_blank_values=True))
            if query.get("code_challenge") != fresh["challenge"]:
                return self.reject("id auth received stale code_challenge")
            if query.get("redirect_state") != fresh["state"]:
                return self.reject("id auth received stale state")
            try:
                settings = json.loads(base64.b64decode(query.get("app_settings", "") + "==").decode())
                if settings.get("service_groups", {}).get("oid") != fresh["oid"]:
                    return self.reject("id auth received stale oid")
            except Exception:
                return self.reject("id auth app_settings is invalid")
            body = "window.init=" + json.dumps(
                {"auth": {"access_token": fresh["auth_token"], "anonymous_token": fresh["anonymous"]}},
                separators=(",", ":"),
            ) + ";"
            self.text_response(200, body)
            return

        if port == 18084 and parsed.path == "/api/v1/vkid_auth/silent/grey_login":
            query = dict(urllib.parse.parse_qsl(parsed.query, keep_blank_values=True))
            if not runtime["connected"]:
                return self.reject("silent login reached before connect_authorize")
            if query.get("state") != fresh["state"]:
                return self.reject("silent login received stale state")
            if query.get("email") != "user@example.test":
                return self.reject("silent login received wrong email")
            if query.get("payload") != fresh["payload"]:
                return self.reject("silent login did not use response-produced payload")
            runtime["silent_ok"] = True
            self.text_response(
                200,
                "<html>authenticated</html>",
                {"Set-Cookie": f"session={fresh['session']}; Path=/; HttpOnly"},
            )
            return

        if port == 18085 and parsed.path == "/assert":
            cookie = self.headers.get("Cookie", "")
            ok = runtime["connected"] and runtime["silent_ok"] and f"session={fresh['session']}" in cookie
            if ok:
                return self.json_response(200, {"authenticated": True})
            return self.json_response(401, {"authenticated": False, "cookie": cookie})

        self.reject(f"unexpected GET {port} {self.path}")

    def do_POST(self):
        port = self.server.server_port
        parsed = urllib.parse.urlparse(self.path)
        values = form(self)

        if port == 18082 and parsed.path == "/method/auth.validateAccount":
            expected = {
                "login": "user@example.test",
                "client_id": "7539952",
                "mail_token": fresh["state"],
                "oid": fresh["oid"],
                "code_challenge": fresh["challenge"],
                "code_challenge_method": "S256",
                "anonymous_token": fresh["anonymous"],
            }
            for key, value in expected.items():
                if values.get(key) != value:
                    return self.reject(f"validateAccount {key} mismatch")
            device = values.get("device_id", "")
            if not device or device == "DeviceId_abcdefghijkl":
                return self.reject("device_id was not generated at runtime")
            runtime["device_id"] = device
            return self.json_response(200, {"response": {"sid": fresh["sid"]}})

        if port == 18082 and parsed.path == "/method/vkidmail.checkPassword":
            if values.get("sid") != fresh["sid"]:
                return self.reject("password step stale sid")
            if values.get("password") != "secret":
                return self.reject("password was not supplied as user input")
            if values.get("anonymous_token") != fresh["anonymous"]:
                return self.reject("password step stale anonymous token")
            return self.json_response(200, {"response": {"next_step": "on_success_validation"}})

        if port == 18082 and parsed.path == "/method/auth.onSuccessValidation":
            if values.get("sid") != fresh["sid"]:
                return self.reject("onSuccess stale sid")
            if values.get("anonymous_token") != fresh["anonymous"]:
                return self.reject("onSuccess stale anonymous token")
            return self.json_response(200, {"response": {"next_step": "connect_authorize"}})

        if port == 18083 and parsed.path == "/" and dict(urllib.parse.parse_qsl(parsed.query)).get("act") == "connect_authorize":
            if values.get("auth_token") != fresh["auth_token"]:
                return self.reject("connect stale auth token")
            if values.get("sid") != fresh["sid"]:
                return self.reject("connect stale sid")
            if values.get("device_id") != runtime["device_id"]:
                return self.reject("connect device_id differs from generated validate device")
            if values.get("service_group") != "oid_" + fresh["oid"]:
                return self.reject("connect service_group did not derive fresh oid")
            if values.get("oauth_state") != fresh["state"]:
                return self.reject("connect stale oauth state")
            raw_to = values.get("to", "")
            try:
                decoded = base64.b64decode(raw_to + "=" * ((4 - len(raw_to) % 4) % 4)).decode()
            except Exception:
                return self.reject("connect to is not valid base64")
            if decoded != expected_to_decoded():
                return self.reject("connect to was not rebuilt from fresh upstream values")
            runtime["connected"] = True
            return self.json_response(200, {"response": {"next_step_url": next_url()}})

        self.reject(f"unexpected POST {port} {self.path}")


def serve(port):
    ThreadingHTTPServer((HOST, port), Handler).serve_forever()


if __name__ == "__main__":
    ports = [18080, 18081, 18082, 18083, 18084, 18085]
    for port in ports:
        threading.Thread(target=serve, args=(port,), daemon=True).start()
    print("AUTH mock ready", flush=True)
    threading.Event().wait()
