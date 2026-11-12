#!/usr/bin/env bash
# ==============================================================================
# Zenith Audio Engine — Universal 1-Click Linux Installer
# Supported OS: Ubuntu, Debian, Linux Mint, Pop!_OS, Arch Linux, Manjaro, Fedora
# ==============================================================================
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${CYAN}${BOLD}"
echo "================================================================"
echo "    ⚡ ZENITH AUDIO ENGINE — 1-CLICK UNIVERSAL INSTALLER       "
echo "        Ultra-Low-Latency Audio Streaming: Linux → Android      "
echo "================================================================"
echo -e "${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 1. Detect Package Manager & Install Dependencies
echo -e "${YELLOW}[1/4] Detecting Linux Distribution & Dependencies...${NC}"

if command -v apt-get &>/dev/null; then
    echo -e "${GREEN}[✓] Debian/Ubuntu based system detected (APT)${NC}"
    sudo apt-get update -qq || true
    sudo apt-get install -y build-essential pkg-config libpipewire-0.3-dev libopus-dev
    sudo apt-get install -y librnnoise-dev || \
        echo -e "${YELLOW}[!] librnnoise-dev not found; RNNoise will be disabled.${NC}"
elif command -v pacman &>/dev/null; then
    echo -e "${GREEN}[✓] Arch Linux based system detected (Pacman)${NC}"
    sudo pacman -S --needed --noconfirm base-devel pkgconf pipewire opus
    sudo pacman -S --needed --noconfirm rnnoise || \
        echo -e "${YELLOW}[!] rnnoise not found; RNNoise will be disabled.${NC}"
elif command -v dnf &>/dev/null; then
    echo -e "${GREEN}[✓] Fedora/RHEL based system detected (DNF)${NC}"
    sudo dnf install -y gcc-c++ make pkgconfig pipewire-devel opus-devel
    sudo dnf install -y rnnoise-devel || \
        echo -e "${YELLOW}[!] rnnoise-devel not found; RNNoise will be disabled.${NC}"
elif command -v zypper &>/dev/null; then
    echo -e "${GREEN}[✓] openSUSE based system detected (Zypper)${NC}"
    sudo zypper install -y gcc-c++ make pkg-config pipewire-devel libopus-devel
    sudo zypper install -y rnnoise-devel || \
        echo -e "${YELLOW}[!] rnnoise-devel not found; RNNoise will be disabled.${NC}"
else
    echo -e "${YELLOW}[!] Warning: Unknown package manager. Checking build tools...${NC}"
    if ! command -v pkg-config &>/dev/null || ! command -v g++ &>/dev/null; then
        echo -e "${RED}[✗] Please install g++, pkg-config, libpipewire-0.3 and libopus headers manually.${NC}"
        exit 1
    fi
    echo -e "${YELLOW}[!] RNNoise is optional; install its development package to enable microphone denoising.${NC}"
fi

# 2. Build Zenith C++ Server
echo -e "\n${YELLOW}[2/4] Compiling Zenith C++ Server Engine...${NC}"
make -C server clean
make -C server -j"$(nproc)"

if [ ! -f "server/bin/zenith-server" ]; then
    echo -e "${RED}[✗] Compilation failed! Binary not found.${NC}"
    exit 1
fi
if pkg-config --exists rnnoise 2>/dev/null; then
    echo -e "${GREEN}[✓] RNNoise microphone noise suppression enabled.${NC}"
else
    echo -e "${YELLOW}[!] RNNoise development files not found; microphone noise suppression will be disabled.${NC}"
    echo -e "${YELLOW}    Install rnnoise-devel (Fedora/openSUSE), librnnoise-dev (Debian/Ubuntu) or rnnoise (Arch) and rebuild.${NC}"
fi
echo -e "${GREEN}[✓] Zenith server compiled successfully!${NC}"

# 3. Install Binary
echo -e "\n${YELLOW}[3/4] Installing zenith-server binary...${NC}"
mkdir -p "$HOME/.local/bin"
install -m 755 "server/bin/zenith-server" "$HOME/.local/bin/.zenith-server.new"
mv -f "$HOME/.local/bin/.zenith-server.new" "$HOME/.local/bin/zenith-server"
install -m 755 scripts/zenith-usb-mode.sh "$HOME/.local/bin/zenith-usb-mode.sh"

# Add ~/.local/bin to PATH once per rc file
add_path_line() {
    local rc="$1"
    [ -e "$rc" ] || touch "$rc"
    if ! grep -qxF 'export PATH="$HOME/.local/bin:$PATH"' "$rc"; then
        echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$rc"
    fi
}
add_path_line "$HOME/.bashrc"
if command -v zsh &>/dev/null; then
    add_path_line "$HOME/.zshrc"
fi
echo -e "${GREEN}[✓] Installed to: $HOME/.local/bin/zenith-server${NC}"

# 4. Configure & Start Systemd Background Service
echo -e "\n${YELLOW}[4/4] Setting up systemd background service...${NC}"
SERVICE_DIR="$HOME/.config/systemd/user"
mkdir -p "$SERVICE_DIR"

UNIT_INSTALLED=0
if [ -f scripts/zenith-server.service ]; then
    install -m 644 scripts/zenith-server.service "$SERVICE_DIR/zenith-server.service"
    UNIT_INSTALLED=1
else
    echo -e "${RED}[✗] scripts/zenith-server.service not found; the service was not installed.${NC}"
fi

if [ "$UNIT_INSTALLED" -eq 1 ]; then
    systemctl --user daemon-reload || true
    if systemctl --user enable zenith-server.service && systemctl --user restart zenith-server.service; then
        SERVICE_OK=1
    else
        SERVICE_OK=0
        echo -e "${YELLOW}[!] Could not start the user service. Check 'systemctl --user status zenith-server'.${NC}"
        echo -e "${YELLOW}    On a headless session, enable lingering: sudo loginctl enable-linger \"$USER\".${NC}"
    fi
else
    SERVICE_OK=0
fi

# Open firewall port 59100/udp if ufw is active
if command -v ufw &>/dev/null && sudo ufw status 2>/dev/null | grep -q "Status: active"; then
    echo -e "${YELLOW}[!] Opening UDP port 59100 in UFW. This is an unauthenticated LAN stream;${NC}"
    echo -e "${YELLOW}    only use it on a trusted network.${NC}"
    sudo ufw allow 59100/udp comment "Zenith Audio Server" >/dev/null 2>&1 || true
fi

# 5. Display Active IP & Status
echo -e "\n${CYAN}================================================================${NC}"
echo -e "${GREEN}${BOLD}       🎉 ZENITH AUDIO SERVER INSTALLED!                       ${NC}"
echo -e "${CYAN}================================================================${NC}"
if [ "${SERVICE_OK:-0}" -eq 1 ]; then
    echo -e "Service Status:  ${GREEN}ACTIVE (systemd --user)${NC}"
else
    echo -e "Service Status:  ${YELLOW}NOT RUNNING — see the message above${NC}"
fi

# Default-route source is reachable by the phone; interface listing is not.
WIFI_IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit }}')
WIFI_IP=${WIFI_IP:-Not detected}
echo -e "Active Host IP:  ${BOLD}${CYAN}$WIFI_IP${NC}"
echo -e "Port:            ${BOLD}59100 (UDP)${NC}"
echo -e "\nQuick Controls:"
echo -e "  Check Status:  ${YELLOW}systemctl --user status zenith-server${NC}"
echo -e "  View Logs:     ${YELLOW}journalctl --user -u zenith-server -f${NC}"
echo -e "  Restart:       ${YELLOW}systemctl --user restart zenith-server${NC}"
echo -e "  Stop:          ${YELLOW}systemctl --user stop zenith-server${NC}"
echo -e "${CYAN}================================================================${NC}"
