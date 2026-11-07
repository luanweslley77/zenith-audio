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
cp "server/bin/zenith-server" "$HOME/.local/bin/zenith-server"
chmod +x "$HOME/.local/bin/zenith-server"

# Add ~/.local/bin to PATH in bashrc / zshrc if missing
if [[ ":$PATH:" != *":$HOME/.local/bin:"* ]]; then
    echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$HOME/.bashrc"
    [ -f "$HOME/.zshrc" ] && echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$HOME/.zshrc"
fi
echo -e "${GREEN}[✓] Installed to: $HOME/.local/bin/zenith-server${NC}"

# 4. Configure & Start Systemd Background Service
echo -e "\n${YELLOW}[4/4] Setting up systemd background service...${NC}"
SERVICE_DIR="$HOME/.config/systemd/user"
mkdir -p "$SERVICE_DIR"

cat << EOF > "$SERVICE_DIR/zenith-server.service"
[Unit]
Description=Zenith Ultra-Low-Latency Audio Streaming Server
After=pipewire.service sound.target network.target

[Service]
Type=simple
ExecStart=%h/.local/bin/zenith-server --port 59100 --bitrate 320
Restart=always
RestartSec=2

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable zenith-server.service
systemctl --user restart zenith-server.service

# Open firewall port 59100/udp if ufw is active
if command -v ufw &>/dev/null && sudo ufw status | grep -q "Status: active"; then
    echo -e "${YELLOW}[!] Opening UDP port 59100 in UFW firewall...${NC}"
    sudo ufw allow 59100/udp comment "Zenith Audio Server" >/dev/null 2>&1 || true
fi

# 5. Display Active IP & Status
echo -e "\n${CYAN}================================================================${NC}"
echo -e "${GREEN}${BOLD}       🎉 ZENITH AUDIO SERVER INSTALLED & RUNNING!             ${NC}"
echo -e "${CYAN}================================================================${NC}"
echo -e "Service Status:  ${GREEN}ACTIVE (systemd --user)${NC}"

# Find IPs
WIFI_IP=$(ip -4 addr show | grep -oP '(?<=inet\s)\d+(\.\d+){3}' | grep -v '127.0.0.1' | head -n 1 || echo "Not detected")
echo -e "Active Host IP:  ${BOLD}${CYAN}$WIFI_IP${NC}"
echo -e "Port:            ${BOLD}59100 (UDP)${NC}"
echo -e "\nQuick Controls:"
echo -e "  Check Status:  ${YELLOW}systemctl --user status zenith-server${NC}"
echo -e "  View Logs:     ${YELLOW}journalctl --user -u zenith-server -f${NC}"
echo -e "  Restart:       ${YELLOW}systemctl --user restart zenith-server${NC}"
echo -e "  Stop:          ${YELLOW}systemctl --user stop zenith-server${NC}"
echo -e "${CYAN}================================================================${NC}"
