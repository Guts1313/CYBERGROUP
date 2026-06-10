# Generates a self-signed TLS cert for local nginx-edge testing.
# No openssl install needed — runs it in a throwaway container.
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
if ((Test-Path "$here\edge.crt") -and (Test-Path "$here\edge.key")) {
    Write-Host "edge.crt / edge.key already exist - skipping. Delete them to regenerate."
    exit 0
}
docker run --rm -v "${here}:/out" alpine/openssl req -x509 -newkey rsa:2048 -nodes `
    -keyout /out/edge.key -out /out/edge.crt -days 365 `
    -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
Write-Host "Wrote edge.crt + edge.key to $here"
