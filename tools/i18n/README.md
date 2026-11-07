# Translation tools / 翻译工具

These commands use only the Python 3.10+ standard library. They need neither a JDK nor an Android SDK and can be run from the repository root.

这些命令只依赖 Python 3.10+ 标准库，不需要 JDK 或 Android SDK，在仓库根目录运行即可。

```sh
python3 tools/i18n/translations.py check
python3 tools/i18n/translations.py export --output translations.csv
python3 -m unittest discover -s tools/i18n -v
```

## Resource checks / 资源检查

`check` compares `string`, `plurals` and `string-array` entries in `strings*.xml` with the default English `values/` folder. It reports missing and extra keys by resource type, allows entries to move between files, and excludes default entries marked `translatable="false"`. It also detects malformed XML, duplicate resources, invalid plural quantities, a missing plural `other` item, array length differences and dropped format arguments.

The placeholder check is intentionally lightweight: it compares the **number** of substitutions in strings, array items and the plural `other` form. It does not require a particular argument order and does not compare every plural form. Languages may use different plural categories; for example, Arabic uses six categories and its `one` and `two` forms may omit a numeric argument. Array lengths must match, but plural category sets need not match English.

Language folders are discovered automatically, including legacy Android language codes and BCP 47 folders such as `values-b+zh+Hant`. Configuration overlays such as `values-night` and `values-zh-v31` are ignored. The current repository has **10 UI languages**, including Arabic with right-to-left text; `check` reports the number it actually discovers.

Exit codes are **0** for success, **1** for reported translation differences and **2** for invalid resources or I/O errors. The `cli (Python)` job in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) runs these tests and this check on Python 3.12.

This tool does **not** replace `app/src/test/.../StringResourcesTest.kt`. The JVM tests remain authoritative for exact placeholder sets where applicable, CLDR plural categories and app language registration. Folder discovery alone cannot prove that a language is registered by the app. Run the JVM tests after editing translations:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.i18n.StringResourcesTest'
```

`check` 以英文 `values/` 为基准，对比各语言 `strings*.xml` 中的字符串、复数和字符串数组，按资源类型报告缺失和多余 key。条目可以跨文件移动；英文里标为 `translatable="false"` 的条目不参与对比。工具还检查 XML 语法、重复资源、非法复数数量词、缺少 `other`、数组长度差异和格式参数遗漏。

占位符检查只做**数量对比**：覆盖普通字符串、数组项和复数的 `other` 项，不要求参数顺序一致，也不逐项强求所有复数形式一致。不同语言可以使用不同复数类别，例如 Arabic 有六类，`one`、`two` 形式可以不写数字参数。数组长度必须一致，复数类别集合则不要求与英文一致。

工具自动发现语言目录，支持安卓旧语言代码及 `values-b+zh+Hant` 这样的 BCP 47 目录，忽略 `values-night`、`values-zh-v31` 等配置覆盖目录。当前仓库共有 **10 种 UI 语言**，包含从右向左书写的 Arabic；`check` 会报告实际发现的语种数。

返回码：**0** 为通过，**1** 为发现译文差异，**2** 为资源无效或文件读写错误。现有 [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) 的 `cli (Python)` job 会在 Python 3.12 下运行工具测试和资源检查。

本工具**不能替代** `app/src/test/.../StringResourcesTest.kt`。适用资源的精确占位符集合、CLDR 复数规则和 App 语言注册仍以 JVM 测试为准；仅发现资源目录不能证明语言已在 App 中注册。修改译文后还要运行上面的 JVM 测试。

## Reference CSV / 翻译对照表

`export` writes UTF-8 CSV with a BOM for spreadsheet applications. Its columns are:

- `key`, `type`: resource name and type.
- `item`: blank for a string, a quantity for a plural, or a zero-based array index.
- `source`: default English file relative to the resource directory.
- `context`: XML comments immediately before the English entry.
- One column per discovered resource folder: `values`, `values-zh`, `values-b+zh+Hant`, then the other languages, including `values-ar`.

Each plural quantity and array item gets its own row. Quantities used only by a translation are included; missing items remain blank rather than being filled with English. Inline XML, Android backslash escapes, placeholders, commas, newlines and right-to-left text are preserved as cell content. The parent directory of `--output` must exist, and an existing file is overwritten.

The CSV is for reading and translation review only, **not for automatic import**. Do not commit generated CSV files. Edit the XML resources, preserve their escaping and formatting arguments, then run both the Python checks and the JVM resource tests.

`export` 生成带 UTF-8 BOM 的 CSV，方便表格软件打开。列包括：

- `key`、`type`：资源名称和类型。
- `item`：字符串留空，复数填数量词，数组填从零开始的下标。
- `source`：英文来源文件相对于资源目录的路径。
- `context`：英文条目前相邻的 XML 注释。
- 每个实际发现的资源目录一列：`values`、`values-zh`、`values-b+zh+Hant`，然后是包括 `values-ar` 在内的其他语言。

每个复数数量词和数组项单独占一行；只在某种语言中使用的复数数量词也会导出，缺失项留空，不自动回填英文。内联 XML、安卓反斜杠转义、占位符、逗号、换行和从右向左文本都保留在单元格中。`--output` 的父目录必须已存在，同名文件会被覆盖。

CSV 只供阅读和翻译核对，**不支持自动导入**，生成的 CSV 不要提交到仓库。贡献译文时请修改 XML，保留转义和格式参数，再运行 Python 检查及 JVM 资源测试。
