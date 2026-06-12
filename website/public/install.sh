#!/usr/bin/env bash

# Nox Installer Script for macOS and Linux
# Installs nox, noxc, nox-lsp, and noxfmt into ~/.nox/bin

set -euo pipefail

# 1. Detect OS and architecture
OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
    Linux)
        OS_NAME="linux"
        ;;
    Darwin)
        OS_NAME="macos"
        ;;
    *)
        echo "Error: Unsupported OS '$OS'. This installer only supports macOS and Linux." >&2
        exit 1
        ;;
esac

# We map architecture to what's built in CI
# CI currently builds: macos-arm64, linux-x64
if [ "$OS_NAME" = "macos" ]; then
    # Default macOS to arm64 as built in release flow
    OS_ARCH="macos-arm64"
else
    # Default Linux to x64
    OS_ARCH="linux-x64"
fi

echo "Detected platform: $OS_NAME ($ARCH)"
echo "Targeting binary package: $OS_ARCH"

# 2. Get the latest release version from GitHub
echo "Fetching latest release version from deepsarda/Nox..."

# Try GitHub API first
LATEST_TAG=$(curl -s "https://api.github.com/repos/deepsarda/Nox/releases" | \
             grep -o '"tag_name": "[^"]*' | \
             grep -v 'vscode-' | \
             grep -v 'intellij-' | \
             head -n 1 | \
             cut -d'"' -f4 || true)

# If API failed or was rate limited, try redirect check
if [ -z "$LATEST_TAG" ]; then
    LATEST_URL=$(curl -s -o /dev/null -w "%{url_effective}" "https://github.com/deepsarda/Nox/releases/latest" || true)
    if [ -n "$LATEST_URL" ] && [[ "$LATEST_URL" == */tag/* ]]; then
        LATEST_TAG=$(basename "$LATEST_URL")
    fi
fi

# Fallback tag if all else fails
if [ -z "$LATEST_TAG" ]; then
    echo "Warning: Could not fetch latest release tag dynamically. Defaulting to v0.0.0."
    LATEST_TAG="v0.0.0"
fi

VERSION="${LATEST_TAG#v}"
echo "Latest version: $LATEST_TAG (v$VERSION)"

# 3. Create target directories
INSTALL_DIR="$HOME/.nox"
BIN_DIR="$INSTALL_DIR/bin"
mkdir -p "$BIN_DIR"

# 4. Download the tarball
TEMP_FILE=$(mktemp)
DOWNLOAD_URL="https://github.com/deepsarda/Nox/releases/download/${LATEST_TAG}/nox-${VERSION}-${OS_ARCH}.tar.gz"

echo "Downloading $DOWNLOAD_URL..."
if ! curl -fsSL "$DOWNLOAD_URL" -o "$TEMP_FILE"; then
    echo "Error: Failed to download release asset from $DOWNLOAD_URL" >&2
    rm -f "$TEMP_FILE"
    exit 1
fi

# 5. Extract and install
echo "Extracting archive..."
tar -xzf "$TEMP_FILE" -C "$INSTALL_DIR"

EXTRACTED_DIR="$INSTALL_DIR/nox-${VERSION}-${OS_ARCH}"

if [ -d "$EXTRACTED_DIR/bin" ]; then
    echo "Installing binaries to $BIN_DIR..."
    cp -r "$EXTRACTED_DIR/bin/"* "$BIN_DIR/"
    chmod +x "$BIN_DIR"/*
else
    echo "Error: Extracted archive did not contain a bin directory at $EXTRACTED_DIR/bin" >&2
    rm -rf "$EXTRACTED_DIR" "$TEMP_FILE"
    exit 1
fi

# 6. Cleanup
rm -rf "$EXTRACTED_DIR" "$TEMP_FILE"

# Write version file
echo "$LATEST_TAG" > "$INSTALL_DIR/version"

echo ""
echo "========================================="
echo " Nox has been successfully installed!"
echo "========================================="
echo "Version: $LATEST_TAG"
echo "Location: $BIN_DIR"
echo ""
echo "Available binaries:"
ls -lh "$BIN_DIR" | grep -v 'total' | awk '{print "  - " $9}'
echo ""

# 7. Help configure PATH
SHELL_NAME=$(basename "$SHELL")
RC_FILE=""

case "$SHELL_NAME" in
    bash)
        if [ -f "$HOME/.bash_profile" ]; then
            RC_FILE="$HOME/.bash_profile"
        else
            RC_FILE="$HOME/.bashrc"
        fi
        ;;
    zsh)
        RC_FILE="$HOME/.zshrc"
        ;;
    fish)
        RC_FILE="$HOME/.config/fish/config.fish"
        ;;
    *)
        RC_FILE="$HOME/.profile"
        ;;
esac

# Check if already in PATH
if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
    echo "To add Nox to your PATH, add the following line to your shell profile ($RC_FILE):"
    echo ""
    if [ "$SHELL_NAME" = "fish" ]; then
        echo "  fish_add_path $BIN_DIR"
    else
        echo "  export PATH=\"\$PATH:$BIN_DIR\""
    fi
    echo ""
    
    # Prompt to automatically add it
    if [ -t 0 ]; then
        read -p "Would you like to automatically add Nox to your PATH in $RC_FILE? [y/N] " -n 1 -r
        echo ""
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            if [ "$SHELL_NAME" = "fish" ]; then
                echo "fish_add_path $BIN_DIR" >> "$RC_FILE"
            else
                echo "" >> "$RC_FILE"
                echo "# Nox binaries" >> "$RC_FILE"
                echo "export PATH=\"\$PATH:$BIN_DIR\"" >> "$RC_FILE"
            fi
            echo "Added PATH export to $RC_FILE. Restart your terminal or run 'source $RC_FILE' to update your current session."
        fi
    fi
else
    echo "Nox is already in your PATH."
fi
