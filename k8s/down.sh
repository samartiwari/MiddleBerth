#!/usr/bin/env bash
# Delete the whole cluster. Nothing of it survives.
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
kind delete cluster --name middleberth
