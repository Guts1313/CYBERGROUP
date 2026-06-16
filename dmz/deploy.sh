#!/usr/bin/env bash
# One-command deploy for the admin dashboard (active sessions + login events).
# Run the SAME line on every VM, after pulling the latest code:
#
#     cd ~/CYBERGROUP && git pull && bash dmz/deploy.sh
#
# The script auto-detects which VM it is (by its static lab IP) and runs ONLY
# that VM's step — so you type the same thing everywhere, nothing else:
#   IdP      10.0.20.10  -> grant the admin role view-users/clients/events (kcadm)
#   Services 10.0.30.10  -> rebuild + restart the backend container
#   DMZ      10.0.10.10  -> ensure nginx is up (the console HTML is a live mount)
if [ "$(id -u)" -ne 0 ]; then echo "(elevating with sudo...)"; exec sudo bash "$0" "$@"; fi
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"          # .../CYBERGROUP/dmz
have_ip(){ { ip -4 addr 2>/dev/null; hostname -I 2>/dev/null; } | grep -qw "$1"; }

if have_ip 10.0.20.10; then
  echo ">> IdP VM (Keycloak): granting admin dashboard roles"
  bash "$DIR/lab-kc-admin-roles.sh"
elif have_ip 10.0.30.10; then
  echo ">> Services VM (backend): rebuilding + restarting the backend container"
  cd "$DIR"
  docker compose -f compose.backend.yml up -d --build
elif have_ip 10.0.10.10; then
  echo ">> DMZ VM (nginx): console HTML is served live from the mount; ensuring nginx is up"
  cd "$DIR"
  docker compose -f compose.edge.yml up -d
else
  echo "!! No known lab IP (10.0.10.10 / 10.0.20.10 / 10.0.30.10) found on this host."
  echo "   See dmz/RUNBOOK.md and run this VM's step manually."
  exit 1
fi
echo ">> done. Re-login as admin-test to pick up the new roles, then open the dashboard."
