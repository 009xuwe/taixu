#!/usr/bin/env python3
import os
import hashlib
import json
import zipfile
import shutil
import ssl
import urllib.request

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PLUGIN_DIR = os.path.join(ROOT_DIR, "assets", "plugins", "cc-switch")
DIST_DIR = os.path.join(ROOT_DIR, "dist", "plugins")
OUTPUT_PACKAGE = os.path.join(DIST_DIR, "taixu-plugin-cc-switch-v1.0.0-arm64.txplugin")
EXPECTED_SHA256 = "0e16293cdab1f6a8416a06242deeda41ddd970cbf36e753f05de8ad89d6cd010"
DOWNLOAD_URL = "https://github.com/Laliet/cc-switch-web/releases/download/v0.21.0/cc-switch-server-linux-aarch64"

def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def build_plugin():
    print("[*] Packaging CC-Switch offline plugin (.txplugin)...")
    os.makedirs(DIST_DIR, exist_ok=True)

    manifest_path = os.path.join(PLUGIN_DIR, "manifest.json")
    payload_dir = os.path.join(PLUGIN_DIR, "payload")
    bin_dir = os.path.join(payload_dir, "bin")
    os.makedirs(bin_dir, exist_ok=True)
    daemon_bin = os.path.join(bin_dir, "cc-switch-daemon")

    if not os.path.isfile(daemon_bin):
        print(f"[*] cc-switch-daemon binary not found locally. Downloading from {DOWNLOAD_URL} ...")
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        req = urllib.request.Request(DOWNLOAD_URL, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, context=ctx, timeout=60) as resp, open(daemon_bin, "wb") as f:
            f.write(resp.read())
        print(f"[+] Downloaded {daemon_bin}")

    current_hash = sha256_file(daemon_bin)
    print(f"[*] cc-switch-daemon SHA256: {current_hash}")
    if current_hash != EXPECTED_SHA256:
        print(f"[!] Warning: binary hash does not match expected ({EXPECTED_SHA256})")

    if not os.path.isfile(manifest_path):
        raise FileNotFoundError(f"Missing manifest at {manifest_path}")

    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    print(f"[*] Plugin ID: {manifest['id']}, Version: {manifest['version']}")

    # Build ZIP package (.txplugin)
    if os.path.exists(OUTPUT_PACKAGE):
        os.remove(OUTPUT_PACKAGE)

    with zipfile.ZipFile(OUTPUT_PACKAGE, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(manifest_path, "manifest.json")
        for root, dirs, files in os.walk(payload_dir):
            for file in files:
                abs_path = os.path.join(root, file)
                rel_path = os.path.relpath(abs_path, PLUGIN_DIR)
                arcname = rel_path.replace(os.sep, "/")
                zf.write(abs_path, arcname)

    pkg_hash = sha256_file(OUTPUT_PACKAGE)
    pkg_size = os.path.getsize(OUTPUT_PACKAGE)
    print(f"[+] Package created: {OUTPUT_PACKAGE}")
    print(f"    Size: {pkg_size} bytes ({pkg_size / (1024*1024):.2f} MB)")
    print(f"    SHA256: {pkg_hash}")

if __name__ == "__main__":
    build_plugin()
