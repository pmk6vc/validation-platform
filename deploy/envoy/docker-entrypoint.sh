#!/bin/sh
set -e

# Replace the JWKS placeholder in envoy.yaml with the base64url-encoded JWT_SECRET.
# This runs at container start so the secret doesn't need to be baked into the config file.

if [ -z "${JWT_SECRET:-}" ]; then
    echo "Warning: JWT_SECRET not set. JWT auth will reject all tokens." >&2
    # Use a dummy key so Envoy's config still parses
    B64URL_SECRET="ZHVtbXk"
else
    B64URL_SECRET=$(echo -n "$JWT_SECRET" | python3 -c "import sys,base64; print(base64.urlsafe_b64encode(sys.stdin.buffer.read()).rstrip(b'=').decode())")
fi

sed "s|REPLACE_WITH_BASE64URL_SECRET|${B64URL_SECRET}|g" /etc/envoy/envoy.yaml > /tmp/envoy-resolved.yaml

exec envoy -c /tmp/envoy-resolved.yaml "$@"
