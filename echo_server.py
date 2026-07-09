from http.server import HTTPServer, BaseHTTPRequestHandler
import json

class EchoHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        response = {
            "method": "GET",
            "path": self.path,
            "headers": dict(self.headers)
        }
        self.wfile.write(json.dumps(response).encode())

    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length) if content_length > 0 else b''

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        response = {
            "method": "POST",
            "path": self.path,
            "headers": dict(self.headers),
            "body": body.decode('utf-8') if body else None
        }
        self.wfile.write(json.dumps(response).encode())

    def log_message(self, format, *args):
        print(f"[ECHO] {self.address_string()} - {args[0]}")

if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 8081), EchoHandler)
    print("Echo server running on http://127.0.0.1:8081")
    server.serve_forever()