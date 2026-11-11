#!/usr/bin/env python3
"""Inspect Android string resources and export a translation-reference CSV."""

import argparse
import csv
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Tuple


DEFAULT_RES_DIR = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res"
RESOURCE_TYPES = {"string", "plurals", "string-array"}
QUANTITIES = ("zero", "one", "two", "few", "many", "other")
LANGUAGE_DIRECTORY = re.compile(
    r"values-(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z0-9]+(?:\+[A-Za-z0-9]+)*)"
)
# This deliberately counts substitutions rather than judging locale-specific argument order.
# An escaped literal percent is text rather than a substitution, so it matches here as a whole
# and is skipped below: otherwise the percent left over from a pair starts a new conversion and
# whatever word follows it (often a different one in each language) is counted as an argument.
FORMAT_ARGUMENT = re.compile(
    r"%%|%(?![%n])(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT])?"
    r"[bBhHsScCdoxXeEfFgGaA]"
)
ET.register_namespace("xliff", "urn:oasis:names:tc:xliff:document:1.2")

ResourceKey = Tuple[str, str]
Catalog = Dict[ResourceKey, "Resource"]


@dataclass
class Resource:
    source: str
    context: str
    translatable: bool
    items: Dict[str, str]


def content_xml(node: ET.Element) -> str:
    """Retain inline markup and Android backslash escapes for spreadsheet review."""
    return (node.text or "") + "".join(
        ET.tostring(child, encoding="unicode") for child in node
    )


def read_directory(directory: Path) -> Catalog:
    catalog = {}
    for path in sorted(directory.glob("strings*.xml")):
        parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
        try:
            root = ET.parse(path, parser=parser).getroot()
        except ET.ParseError as error:
            raise ValueError("{}: {}".format(path, error)) from error
        if root.tag != "resources":
            raise ValueError("{}: expected a <resources> root".format(path))

        comments = []
        for node in root:
            if node.tag is ET.Comment:
                comments.append((node.text or "").strip())
                continue
            context = "\n".join(comments)
            comments = []
            if node.tag not in RESOURCE_TYPES:
                continue
            name = node.get("name")
            if not name:
                raise ValueError("{}: <{}> has no name".format(path, node.tag))
            key = (node.tag, name)
            if key in catalog:
                raise ValueError(
                    "{}: duplicate {}/{} (first defined in {})".format(
                        path, node.tag, name, catalog[key].source
                    )
                )

            if node.tag == "string":
                items = {"": content_xml(node)}
            else:
                items = {}
                for index, item in enumerate(node.findall("item")):
                    if node.tag == "plurals":
                        label = item.get("quantity", "")
                        if label not in QUANTITIES:
                            raise ValueError(
                                "{}: {}/{} has an invalid or missing quantity".format(
                                    path, node.tag, name
                                )
                            )
                    else:
                        label = str(index)
                    if label in items:
                        raise ValueError(
                            "{}: duplicate {}/{}/{}".format(path, node.tag, name, label)
                        )
                    items[label] = content_xml(item)
                if not items:
                    raise ValueError("{}: {}/{} has no items".format(path, node.tag, name))
                if node.tag == "plurals" and "other" not in items:
                    raise ValueError(
                        "{}: plurals/{} requires an 'other' item".format(path, name)
                    )

            catalog[key] = Resource(
                source="{}/{}".format(directory.name, path.name),
                context=context,
                translatable=node.get("translatable") != "false",
                items=items,
            )
    return catalog


def load_catalogs(res_dir: Path) -> Dict[str, Catalog]:
    base = read_directory(res_dir / "values")
    if not base:
        raise ValueError("{}: no string resources found".format(res_dir / "values"))
    directories = [
        path
        for path in res_dir.iterdir()
        if path.is_dir()
        and LANGUAGE_DIRECTORY.fullmatch(path.name)
        and any(path.glob("strings*.xml"))
    ]
    # Keep the two Chinese references next to English; order the remaining folders by name.
    directories.sort(
        key=lambda path: (
            0 if path.name == "values-zh" else 1 if path.name == "values-b+zh+Hant" else 2,
            path.name,
        )
    )
    catalogs = {"values": base}
    for directory in directories:
        catalogs[directory.name] = read_directory(directory)
    return catalogs


def placeholder_count(text: str) -> int:
    return sum(1 for match in FORMAT_ARGUMENT.finditer(text) if match.group(0) != "%%")


def check(catalogs: Dict[str, Catalog]) -> int:
    base = catalogs["values"]
    expected = {key for key, resource in base.items() if resource.translatable}
    problems = []

    for folder, translated in catalogs.items():
        if folder == "values":
            continue
        for kind, name in sorted(expected - translated.keys()):
            problems.append("{}: missing {}/{}".format(folder, kind, name))
        for kind, name in sorted(translated.keys() - expected):
            problems.append("{}: extra {}/{}".format(folder, kind, name))

        for key in sorted(expected & translated.keys()):
            kind, name = key
            reference = base[key].items
            actual = translated[key].items
            if kind == "string-array" and len(reference) != len(actual):
                problems.append(
                    "{}: item count string-array/{}: default={}, translation={}".format(
                        folder, name, len(reference), len(actual)
                    )
                )
            # Plural categories legitimately differ by language. The required 'other'
            # form gives a common lightweight check without imposing English CLDR rules.
            labels = ["other"] if kind == "plurals" else sorted(reference.keys() & actual.keys())
            for label in labels:
                expected_count = placeholder_count(reference[label])
                actual_count = placeholder_count(actual[label])
                if expected_count != actual_count:
                    item = "/{}".format(label) if label else ""
                    problems.append(
                        "{}: placeholder count {}/{}{}: default={}, translation={}".format(
                            folder, kind, name, item, expected_count, actual_count
                        )
                    )

    if problems:
        for problem in problems:
            print(problem)
        print("{} translation resource issue(s).".format(len(problems)))
        return 1
    print(
        "Translation checks passed: {} resources across {} UI languages.".format(
            len(expected), len(catalogs)
        )
    )
    return 0


def export_csv(catalogs: Dict[str, Catalog], output: Path) -> None:
    folders = list(catalogs)
    with output.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["key", "type", "item", "source", "context", *folders])
        for (kind, name), resource in sorted(catalogs["values"].items()):
            if not resource.translatable:
                continue
            entries = [catalogs[folder].get((kind, name)) for folder in folders]
            labels = set().union(*(entry.items.keys() for entry in entries if entry))
            if kind == "plurals":
                ordered_labels = [label for label in QUANTITIES if label in labels]
            elif kind == "string-array":
                ordered_labels = sorted(labels, key=int)
            else:
                ordered_labels = [""]
            for label in ordered_labels:
                writer.writerow(
                    [
                        name,
                        kind,
                        label,
                        resource.source,
                        resource.context,
                        *(
                            entry.items.get(label, "") if entry else ""
                            for entry in entries
                        ),
                    ]
                )
    print("Exported translation reference to {}".format(output))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--res-dir", type=Path, default=DEFAULT_RES_DIR, help="Android resource directory"
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check", help="Check keys, collection structure and placeholder counts")
    export_parser = commands.add_parser(
        "export", help="Write a review CSV; this is not an import format"
    )
    export_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        catalogs = load_catalogs(args.res_dir)
        if args.command == "check":
            return check(catalogs)
        export_csv(catalogs, args.output)
        return 0
    except (OSError, ValueError) as error:
        print("Error: {}".format(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
