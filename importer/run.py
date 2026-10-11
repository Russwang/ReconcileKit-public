"""Explicit package path permits isolated (-I) execution without installing the app."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from reconcilekit_importer.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
