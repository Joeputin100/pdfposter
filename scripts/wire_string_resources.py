#!/usr/bin/env python3
"""Bulk-replace hardcoded user-facing strings in Kotlin sources with
`stringResource(R.string.<key>)` calls.

Strategy:
  1. Parse `app/src/main/res/values/strings.xml` to build a content→key map.
  2. Walk each Kotlin source file in COMPOSE_FILES.
  3. For each `Text("…", …)` literal whose content matches a string in
     strings.xml, rewrite the literal into a `stringResource(R.string.<key>)`
     call (or `stringResource(R.string.<key>, arg)` if the source has
     a `%1$s` / `%1$d` placeholder).
  4. Add `import androidx.compose.ui.res.stringResource` if missing.

Why a script instead of more LLM Edit calls:
  - 300+ hardcoded strings = 300+ Edit tool turns; the previous translation
    agent stalled at the watchdog after ~7 minutes of single-string Edits.
  - This script is deterministic, fast, and doesn't depend on remembering
    the strings.xml schema between iterations.

Usage:
    python3 scripts/wire_string_resources.py [--dry-run]

Out of scope (intentional):
  - `contentDescription = "..."` literals — these are mostly already wired
    or use accessibility-specific phrasing that\'s harder to parse.
  - String concatenation: `"Width: " + value`. Those need composition with
    string templates and are easier to migrate by hand when touched.
  - Multi-arg format strings (%1$s and %2$d in same string) — left alone
    to avoid getting argument order wrong.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STRINGS_XML = REPO_ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"

COMPOSE_FILES = [
    "app/src/main/kotlin/com/posterpdf/MainActivity.kt",
    "app/src/main/kotlin/com/posterpdf/ui/components/LowDpiUpgradeModal.kt",
    "app/src/main/kotlin/com/posterpdf/ui/components/PaperSizeCard.kt",
    "app/src/main/kotlin/com/posterpdf/ui/components/UnitsToggleCard.kt",
    "app/src/main/kotlin/com/posterpdf/ui/components/StorageRetentionDialog.kt",
    "app/src/main/kotlin/com/posterpdf/ui/components/BringYourOwnHelpDialog.kt",
    "app/src/main/kotlin/com/posterpdf/ui/components/PurchaseSheet.kt",
    "app/src/main/kotlin/com/posterpdf/ui/components/ImagePickerHeader.kt",
    "app/src/main/kotlin/com/posterpdf/ui/components/UpscaleProgressBar.kt",
    "app/src/main/kotlin/com/posterpdf/ui/components/CreditBadge.kt",
    "app/src/main/kotlin/com/posterpdf/ui/screens/UpscaleComparisonScreen.kt",
]

STRING_RES_IMPORT = "import androidx.compose.ui.res.stringResource"


def parse_strings_xml() -> dict[str, str]:
    """Return {english_text -> resource_key}. Skip strings with format
    placeholders that take more than one argument (too risky to auto-rewrite)."""
    tree = ET.parse(STRINGS_XML)
    root = tree.getroot()
    mapping: dict[str, str] = {}
    for s in root.findall("string"):
        key = s.get("name")
        if key is None or s.text is None:
            continue
        text = s.text
        # Skip multi-arg format strings — keep manual control there.
        placeholders = re.findall(r"%\d+\$[sdf]", text)
        if len(placeholders) > 1:
            continue
        # Unescape XML escapes that ElementTree might have left in the text.
        # Note: ET already decodes &amp; → &. We need to handle Android\'s
        # backslash-apostrophe escape: in the .xml file it\'s `\'`, in the
        # Kotlin literal it\'s `'`.
        kotlin_form = text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
        if kotlin_form in mapping:
            # Duplicate English text → ambiguous mapping. Skip both to
            # avoid a wrong replacement.
            mapping.pop(kotlin_form, None)
            continue
        # Skip very short strings that are likely punctuation tokens or
        # common single words that could appear in many contexts.
        if len(kotlin_form.strip()) < 2:
            continue
        mapping[kotlin_form] = key
    return mapping


def has_format_placeholder(text: str) -> str | None:
    """Return the placeholder type ('s', 'd', 'f') if exactly one is present,
    else None."""
    matches = re.findall(r"%\d+\$([sdf])", text)
    if len(matches) == 1:
        return matches[0]
    return None


def rewrite_kotlin(path: Path, mapping: dict[str, str], dry_run: bool) -> int:
    """Rewrite Text("...") literals in `path` using `mapping`. Returns the
    number of replacements made."""
    content = path.read_text()
    original = content

    # We intentionally restrict to:
    #   Text("...")
    #   Text("...", style = ...)
    # Not:
    #   Text(text = "...")  (ambiguous with `text = stringResource(...)`)
    #   Text("..." + value) (concatenation; left alone)
    #
    # Pattern: capture whole `Text("...", possibly_more_args)` calls where the
    # first argument is a plain double-quoted string. We use a simple regex
    # because Kotlin string literals don\'t span lines (most of the time).
    pattern = re.compile(
        r'Text\(\s*"([^"\\]*(?:\\.[^"\\]*)*)"\s*(,|\))',
        re.MULTILINE,
    )

    replacements = 0

    def replace(match: re.Match) -> str:
        nonlocal replacements
        literal = match.group(1)
        terminator = match.group(2)  # "," or ")"
        # Decode Kotlin string escapes back to Python form for lookup.
        kotlin_decoded = (
            literal.replace("\\n", "\n")
            .replace('\\"', '"')
            .replace("\\\\", "\\")
        )
        if kotlin_decoded not in mapping:
            return match.group(0)
        key = mapping[kotlin_decoded]
        # Build the replacement.
        replacements += 1
        return f"Text(stringResource(R.string.{key}){terminator}"

    content = pattern.sub(replace, content)

    if replacements > 0 and STRING_RES_IMPORT not in content:
        # Insert the import next to other compose.ui.res imports.
        content = re.sub(
            r"(import androidx\.compose\.ui\.res\.painterResource\n)",
            r"\1" + STRING_RES_IMPORT + "\n",
            content,
            count=1,
        )
        # Fallback: insert near other androidx.compose.ui imports.
        if STRING_RES_IMPORT not in content:
            content = re.sub(
                r"(import androidx\.compose\.ui\.[^\n]+\n)",
                r"\1" + STRING_RES_IMPORT + "\n",
                content,
                count=1,
            )

    if content != original:
        if dry_run:
            print(f"[dry] {path.relative_to(REPO_ROOT)}: {replacements} replacements")
        else:
            path.write_text(content)
            print(f"      {path.relative_to(REPO_ROOT)}: {replacements} replacements")
    return replacements


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    mapping = parse_strings_xml()
    print(f"Loaded {len(mapping)} unambiguous English-text → key mappings.\n")

    total = 0
    for rel in COMPOSE_FILES:
        path = REPO_ROOT / rel
        if not path.exists():
            print(f"[skip] {rel} not found")
            continue
        total += rewrite_kotlin(path, mapping, args.dry_run)

    print(f"\nTotal replacements: {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
