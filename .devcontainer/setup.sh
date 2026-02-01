#!/bin/bash
set -e

# Install Clojure CLI
curl -L -O https://github.com/clojure/brew-install/releases/download/1.12.0.1530/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
rm linux-install.sh

# Pre-download dependencies
cd /workspaces/yakread-sqlite
clojure -P -M:run

echo "Development environment setup complete!"
