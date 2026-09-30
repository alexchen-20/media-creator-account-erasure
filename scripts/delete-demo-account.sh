#!/usr/bin/env sh
set -eu

curl --fail-with-body \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"accountId":"creator-42","userId":"user-42","credentialId":"credential-42"}' \
  http://localhost:8080/media-accounts/delete
