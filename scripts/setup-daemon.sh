#!/usr/bin/env bash
# ==============================================================================
# Zenith Audio Engine — Linux Background Daemon Setup
# ==============================================================================
set -e

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}========================================================${NC}"
echo -e "${GREEN}     ZENITH AUDIO SERVER — SYSTEMD DAEMON SETUP         ${NC}"
echo -e "${CYAN}========================================================${NC}"

# Check for zenith-server binary
SERVER_BIN="$(dirname "$0")/../server/bin/zenith-server"
INSTALL_BIN="$HOME/.local/bin/zenith-server"

if [ ! -f "$SERVER_BIN" ] && [ ! -f "$INSTALL_BIN" ]; then
    echo -e "${YELLOW}[!] Building server binary...${NC}"
    make -C "$(dirname "$0")/../server" -j$(nproc)
fi

mkdir -p "$HOME/.local/bin"
if [ -f "$SERVER_BIN" ]; then
    install -m 755 "$SERVER_BIN" "$INSTALL_BIN.new"
    mv -f "$INSTALL_BIN.new" "$INSTALL_BIN"
    echo -e "${GREEN}[✓] Installed binary to:${NC} $INSTALL_BIN"
fi

# Create systemd user service directory
SERVICE_DIR="$HOME/.config/systemd/user"
mkdir -p "$SERVICE_DIR"

install -m 644 "$(dirname "$0")/zenith-server.service" "$SERVICE_DIR/zenith-server.service"

echo -e "${GREEN}[✓] Created service definition:${NC} $SERVICE_DIR/zenith-server.service"

systemctl --user daemon-reload
systemctl --user enable zenith-server.service
systemctl --user restart zenith-server.service

echo -e "${GREEN}[✓] Zenith Audio Server daemon enabled and started!${NC}"
echo -e "${CYAN}--------------------------------------------------------${NC}"
echo -e "Useful Commands:"
echo -e "  Status:  ${GREEN}systemctl --user status zenith-server${NC}"
echo -e "  Logs:    ${GREEN}journalctl --user -u zenith-server -f${NC}"
echo -e "  Restart: ${GREEN}systemctl --user restart zenith-server${NC}"
echo -e "  Stop:    ${GREEN}systemctl --user stop zenith-server${NC}"
echo -e "${CYAN}========================================================${NC}"
