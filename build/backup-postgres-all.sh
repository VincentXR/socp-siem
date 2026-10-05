#!/usr/bin/env bash
set -euo pipefail

# A full logical backup holds database table fences across the entire roster.
# Run only in a maintenance window with ingress, applications, workers and
# migrations stopped; --maintenance is an explicit acknowledgement of this.
# Usage: backup-postgres-all.sh --maintenance <empty-backup-directory>
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "${script_dir}/backup-postgres-consistent.py" "$@"
