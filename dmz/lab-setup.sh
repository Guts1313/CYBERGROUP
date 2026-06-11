#!/usr/bin/env bash
# CYBERGROUP - n2-test-dmz prep for the DMZ nginx edge.
# Run as:  sudo bash dmz/lab-setup.sh
# Refreshes the Kali signing key, switches to the CDN mirror, installs Docker,
# and prints the network config for the re-IP step.

# auto-elevate so 'bash dmz/lab-setup.sh' works even without sudo
if [ "$(id -u)" -ne 0 ]; then echo "(elevating with sudo...)"; exec sudo bash "$0" "$@"; fi

echo "=== [1/5] fix sudo hostname warning ==="
HN="$(hostname)"
grep -q " ${HN}\$" /etc/hosts || echo "127.0.1.1 ${HN}" >> /etc/hosts
echo "ok"

echo "=== [2/5] refresh Kali keyring + set CDN mirror (fixes NO_PUBKEY + 404s) ==="
wget -q https://archive.kali.org/archive-keyring.gpg -O /usr/share/keyrings/kali-archive-keyring.gpg \
  && echo "keyring ok" || echo "keyring download FAILED (check egress 443)"
cp -n /etc/apt/sources.list /etc/apt/sources.list.bak 2>/dev/null || true
echo "deb [signed-by=/usr/share/keyrings/kali-archive-keyring.gpg] https://kali.download/kali kali-rolling main contrib non-free non-free-firmware" > /etc/apt/sources.list
cat /etc/apt/sources.list

echo "=== [3/5] apt update + install docker.io ==="
apt-get update
apt-get install -y docker.io

echo "=== [4/5] enable + smoke-test docker ==="
systemctl enable --now docker 2>/dev/null
if docker --version 2>/dev/null; then
  docker run --rm hello-world >/dev/null 2>&1 \
    && echo "DOCKER OK (pulled + ran hello-world)" \
    || echo "docker smoke-test FAILED - check egress to docker hub (443)"
else
  echo "docker NOT installed"
fi

echo "=== [5/5] current network config ==="
ip -br a
echo "--- /etc/network/interfaces ---"
cat /etc/network/interfaces 2>/dev/null || echo "(none)"
echo "--- NetworkManager ---"
nmcli -t -f NAME,DEVICE,STATE con show 2>/dev/null || echo "NM not active"
echo "=== DONE ==="
