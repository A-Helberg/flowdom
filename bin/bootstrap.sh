#!/usr/bin/env bash

set -e

SCRIPT_DIR=$(dirname "$0")
source "$SCRIPT_DIR/colors.sh"

# ---------------------------------------------------------------------------
# mise
# ---------------------------------------------------------------------------

if ! command -v mise >/dev/null 2>&1; then
  echo -e "${RED}mise not found — install it first: https://mise.jdx.dev${NC}"
  exit 1
fi

echo -e "${YELLOW}Installing tools from mise.toml${NC}"
mise install

# ---------------------------------------------------------------------------
# JS dependencies
# ---------------------------------------------------------------------------

ROOT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

for dir in example; do
  echo -e "${YELLOW}Installing JS dependencies in ${dir}${NC}"
  (cd "$ROOT_DIR/$dir" && mise exec -- bun install)
done

echo -e "${GREEN}Bootstrap complete${NC}"

