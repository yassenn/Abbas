#!/usr/bin/env bash
# Signed over-the-air model catalogue. Reusable for any app that embeds a public key.
#
#   tools/catalog/catalog.sh keygen        # once: make the signing key (kept OUT of the repo)
#   tools/catalog/catalog.sh publish       # export → verify links/sha256 → sign → catalog/
#   tools/catalog/catalog.sh verify        # check catalog/models.json against its signature
#
# Key: ${CATALOG_KEY:-~/.config/alal/catalog-signing-key.pem} (ECDSA P-256).
# The app verifies SHA256withECDSA over the exact bytes of models.json.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KEY="${CATALOG_KEY:-$HOME/.config/alal/catalog-signing-key.pem}"
KOTLIN="${CATALOG_SOURCE:-$ROOT/app/src/main/java/ai/alal/app/data/ModelConfig.kt}"
OUT_DIR="$ROOT/catalog"
JSON="$OUT_DIR/models.json"

case "${1:-}" in
  keygen)
    if [[ -f "$KEY" ]]; then echo "Key already exists: $KEY"; else
      mkdir -p "$(dirname "$KEY")"; umask 077
      openssl ecparam -name prime256v1 -genkey -noout -out "$KEY"
      echo "Created $KEY (keep it private; back it up offline)"
    fi
    echo "Public key (base64 DER) to embed in the app:"
    openssl ec -in "$KEY" -pubout -outform DER 2>/dev/null | base64 -w0; echo
    ;;
  publish)
    [[ -f "$KEY" ]] || { echo "No signing key; run: $0 keygen" >&2; exit 1; }
    python3 "$ROOT/tools/catalog/export_catalog.py" "$KOTLIN" "$JSON"
    python3 "$ROOT/tools/models/verify_models.py" "$KOTLIN" --out "$ROOT/dist/models-verified.json"
    openssl dgst -sha256 -sign "$KEY" "$JSON" | base64 -w0 > "$JSON.sig"
    openssl dgst -sha256 -verify <(openssl ec -in "$KEY" -pubout 2>/dev/null) -signature <(base64 -d "$JSON.sig") "$JSON"
    echo "Signed: $JSON (+ .sig). Commit and push catalog/ so phones pick it up."
    ;;
  verify)
    [[ -f "$KEY" ]] || { echo "No signing key" >&2; exit 1; }
    openssl dgst -sha256 -verify <(openssl ec -in "$KEY" -pubout 2>/dev/null) -signature <(base64 -d "$JSON.sig") "$JSON"
    ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
