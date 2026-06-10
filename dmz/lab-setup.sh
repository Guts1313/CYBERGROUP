#!/usr/bin/env bash
# CYBERGROUP - n2-test-dmz prep for the DMZ nginx edge.
# Run as:  sudo bash dmz/lab-setup.sh
# Fixes the sudo hostname warning, switches to Kali's CDN apt mirror,
# installs Docker, and prints the network config for the re-IP step.

echo "=== [1/5] fix sudo hostname warning ==="
HN="$(hostname)"
grep -q " ${HN}\$" /etc/hosts || echo "127.0.1.1 ${HN}" >> /etc/hosts
echo "ok"

echo "=== [2/5] switch to Kali CDN apt mirror ==="
cp -n /etc/apt/sources.list /etc/apt/sources.list.bak 2>/dev/null || true
echo "deb http://kali.download/kali kali-rolling main contrib non-free non-free-firmware" > /etc/apt/sources.list
cat /etc/apt/sources.list

echo "=== [3/5] apt update + install docker.io ==="
apt-get update
apt-get install -y docker.io

echo "=== [4/5] enable + smoke-test docker ==="
systemctl enable --now docker
docker --version
docker run --rm hello-world >/dev/null 2>&1 && echo "DOCKER OK (pulled + ran hello-world)" || echo "docker smoke-test FAILED - check egress"

echo "=== [5/5] current network config (paste this back) ==="
ip -br a
echo "--- /etc/network/interfaces ---"
cat /etc/network/interfaces 2>/dev/null || echo "(none)"
echo "--- NetworkManager ---"
nmcli -t -f NAME,DEVICE,STATE con show 2>/dev/null || echo "NM not active"
echo "=== DONE ==="
