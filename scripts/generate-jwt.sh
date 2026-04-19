#!/usr/bin/env bash
set -euo pipefail

# Generate a JWT for agent authentication.
#
# Prerequisites: pip3 install PyJWT
#
# Usage:
#   JWT_SECRET=my-secret ORG_ID=org-123 CLUSTER=prod ./scripts/generate-jwt.sh
#
# Outputs:
#   Line 1: the signed JWT token
#   Line 2: the base64url-encoded secret (for Envoy JWKS config)
#
# The JWT contains claims:
#   - organizationId: the org this agent belongs to
#   - cluster: the cluster this agent runs in
#   - role: the role for RBAC (default: agent)
#   - iat: issued-at timestamp
#   - exp: expiration timestamp

SECRET="${JWT_SECRET:?JWT_SECRET env var required}"
ORG_ID="${ORG_ID:-default-org}"
CLUSTER="${CLUSTER:-validation-sandbox}"
ROLE="${ROLE:-agent}"
EXPIRY_DAYS="${EXPIRY_DAYS:-365}"

# Check for PyJWT
if ! python3 -c "import jwt" 2>/dev/null; then
    echo "Error: PyJWT not installed. Run: pip3 install PyJWT" >&2
    exit 1
fi

python3 -c "
import jwt
import datetime
import base64

secret = '$SECRET'
token = jwt.encode({
    'organizationId': '$ORG_ID',
    'cluster': '$CLUSTER',
    'role': '$ROLE',
    'iat': datetime.datetime.now(datetime.timezone.utc),
    'exp': datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=$EXPIRY_DAYS),
}, secret, algorithm='HS256')

# Base64url-encode the secret for Envoy's JWKS config
b64url_secret = base64.urlsafe_b64encode(secret.encode()).rstrip(b'=').decode()

print(token)
print(b64url_secret)
"
