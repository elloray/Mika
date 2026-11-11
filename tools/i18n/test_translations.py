"""End-to-end tests for the dependency-free translation resource CLI."""

import csv
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("translations.py")


class TranslationCliTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.res_dir = self.root / "res"

    def write_resources(self, folder, body, filename="strings.xml"):
        path = self.res_dir / folder / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("<resources>{}</resources>".format(body), encoding="utf-8")

    def run_cli(self, *arguments):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--res-dir", str(self.res_dir), *arguments],
            capture_output=True,
            text=True,
            encoding="utf-8",
        )

    def test_reports_missing_extra_and_changed_resource_types_together(self):
        self.write_resources(
            "values",
            '<string name="save">Save</string>'
            '<plurals name="tasks"><item quantity="other">Tasks</item></plurals>',
        )
        self.write_resources(
            "values-es",
            '<string name="obsolete">Viejo</string>'
            '<string-array name="tasks"><item>Tareas</item></string-array>',
        )
        result = self.run_cli("check")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("values-es: missing string/save", result.stdout)
        self.assertIn("values-es: missing plurals/tasks", result.stdout)
        self.assertIn("values-es: extra string/obsolete", result.stdout)
        self.assertIn("values-es: extra string-array/tasks", result.stdout)

    def test_fixed_strings_file_moves_and_configuration_overlays(self):
        self.write_resources(
            "values",
            '<string name="app_name" translatable="false">Mika</string>'
            '<string name="save">Save</string>',
        )
        self.write_resources("values-zh", '<string name="save">保存</string>', "strings_ui.xml")
        self.write_resources("values-night", '<string name="night">Night</string>')
        self.write_resources("values-zh-v31", '<string name="versioned">版本</string>')
        result = self.run_cli("check")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("2 UI languages", result.stdout)

    def test_placeholder_count_catches_dropped_arguments_without_requiring_order(self):
        self.write_resources(
            "values",
            '<string name="date">%2$s %3$d</string>'
            '<string name="greeting">Hello %1$s, %2$d</string>',
        )
        self.write_resources(
            "values-zh",
            '<string name="date">%1$d 月 %3$d 日</string>'
            '<string name="greeting">你好 %1$s</string>',
        )
        result = self.run_cli("check")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertNotIn("string/date:", result.stdout)
        self.assertIn(
            "values-zh: placeholder count string/greeting: default=2, translation=1",
            result.stdout,
        )

    def test_arabic_plural_categories_and_optional_count_in_short_forms(self):
        self.write_resources(
            "values",
            '<plurals name="tasks"><item quantity="one">%1$d task</item>'
            '<item quantity="other">%1$d tasks</item></plurals>',
        )
        self.write_resources(
            "values-ar",
            '<plurals name="tasks">'
            '<item quantity="zero">لا مهام</item><item quantity="one">مهمة واحدة</item>'
            '<item quantity="two">مهمتان</item><item quantity="few">%1$d مهام</item>'
            '<item quantity="many">%1$d مهمة</item><item quantity="other">%1$d مهمة</item>'
            '</plurals>',
        )
        result = self.run_cli("check")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_plural_other_count_is_checked(self):
        self.write_resources(
            "values",
            '<plurals name="tasks"><item quantity="other">%1$d tasks</item></plurals>',
        )
        self.write_resources(
            "values-ru",
            '<plurals name="tasks"><item quantity="one">Одна задача</item>'
            '<item quantity="few">Несколько задач</item>'
            '<item quantity="many">Много задач</item>'
            '<item quantity="other">Задачи</item></plurals>',
        )
        result = self.run_cli("check")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("plurals/tasks/other: default=1, translation=0", result.stdout)

    def test_invalid_plural_structures_are_input_errors(self):
        cases = (
            ('<item quantity="one">One</item>', "requires an 'other' item"),
            ('<item quantity="all">All</item><item quantity="other">Other</item>', "quantity"),
            (
                '<item quantity="other">One</item><item quantity="other">Again</item>',
                "duplicate plurals/tasks/other",
            ),
        )
        for items, message in cases:
            with self.subTest(message=message):
                self.write_resources(
                    "values", '<plurals name="tasks">{}</plurals>'.format(items)
                )
                result = self.run_cli("check")
                self.assertEqual(2, result.returncode)
                self.assertIn(message, result.stderr)
                self.assertNotIn("Traceback", result.stderr)

    def test_array_length_and_item_placeholder_count(self):
        self.write_resources(
            "values",
            '<string-array name="choices"><item>%1$s yes</item><item>No</item></string-array>',
        )
        self.write_resources(
            "values-ja",
            '<string-array name="choices"><item>はい</item></string-array>',
        )
        result = self.run_cli("check")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("item count string-array/choices: default=2, translation=1", result.stdout)
        self.assertIn("string-array/choices/0: default=1, translation=0", result.stdout)

    def test_duplicate_keys_and_malformed_xml_name_the_source(self):
        self.write_resources("values", '<string name="save">Save</string>')
        self.write_resources("values", '<string name="save">Again</string>', "strings_more.xml")
        duplicate = self.run_cli("check")
        self.assertEqual(2, duplicate.returncode)
        self.assertIn("duplicate string/save", duplicate.stderr)
        self.assertIn("values/strings.xml", duplicate.stderr)

        (self.res_dir / "values" / "strings_more.xml").unlink()
        self.write_resources("values", '<string name="save">')
        malformed = self.run_cli("check")
        self.assertEqual(2, malformed.returncode)
        self.assertIn("strings.xml", malformed.stderr)
        self.assertNotIn("Traceback", malformed.stderr)

    def test_empty_resource_root_is_an_input_error(self):
        self.res_dir.mkdir()
        result = self.run_cli("check")
        self.assertEqual(2, result.returncode)
        self.assertIn("values", result.stderr)

    def test_escaped_percent_is_not_counted_as_a_format_argument(self):
        self.write_resources(
            "values",
            '<string name="plain">Saved 100%% of the batch</string>'
            '<string name="mixed">Saved %1$d files, 100%% of the batch</string>',
        )
        self.write_resources(
            "values-zh",
            '<string name="plain">已保存这批的 100%%</string>'
            '<string name="mixed">已保存 %1$d 个文件，占这批的 100%%</string>',
        )
        result = self.run_cli("check")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_escaped_percent_does_not_hide_a_dropped_argument(self):
        self.write_resources(
            "values", '<string name="saved">Saved %1$s, 100%% of the batch</string>'
        )
        self.write_resources(
            "values-zh", '<string name="saved">已保存这批的 100%%</string>'
        )
        result = self.run_cli("check")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn(
            "values-zh: placeholder count string/saved: default=1, translation=0",
            result.stdout,
        )

    def test_csv_export_preserves_context_markup_rtl_text_and_plural_rows(self):
        self.write_resources(
            "values",
            '<!-- Confirmation action. --><string name="save">Save, &quot;now&quot;\n'
            '<b>please</b>\\n%1$s</string>'
            '<string name="fixed" translatable="false">Mika</string>'
            '<plurals name="tasks"><item quantity="one">One</item>'
            '<item quantity="other">Many</item></plurals>'
            '<string-array name="choices"><item>Yes</item><item>No</item></string-array>',
        )
        self.write_resources("values-zh", '<string name="save">保存</string>')
        self.write_resources(
            "values-ar",
            '<string name="save">حفظ</string>'
            '<plurals name="tasks"><item quantity="few">عدة مهام</item>'
            '<item quantity="other">مهام</item></plurals>',
        )
        output = self.root / "translations.csv"
        result = self.run_cli("export", "--output", str(output))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(output.read_bytes().startswith(b"\xef\xbb\xbf"))
        with output.open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            self.assertEqual(
                ["key", "type", "item", "source", "context", "values", "values-zh", "values-ar"],
                reader.fieldnames,
            )
            rows = list(reader)

        save = next(row for row in rows if row["key"] == "save")
        self.assertEqual('Save, "now"\n<b>please</b>\\n%1$s', save["values"])
        self.assertEqual("حفظ", save["values-ar"])
        self.assertEqual("Confirmation action.", save["context"])
        self.assertEqual("values/strings.xml", save["source"])
        self.assertNotIn("fixed", {row["key"] for row in rows})
        few = next(row for row in rows if row["key"] == "tasks" and row["item"] == "few")
        self.assertEqual("", few["values"])
        self.assertEqual("عدة مهام", few["values-ar"])
        choices = [row for row in rows if row["key"] == "choices"]
        self.assertEqual([("0", "Yes"), ("1", "No")], [(row["item"], row["values"]) for row in choices])

        first_export = output.read_bytes()
        second = self.run_cli("export", "--output", str(output))
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(first_export, output.read_bytes())


if __name__ == "__main__":
    unittest.main()
