package com.wangzan101.mika.core.cap.root

/**
 * 一条 root 命令会不会改动东西:只读的直接跑,会改的询问时先问用户。拿不准一律当作会改:
 * 不认识的命令、命令替换、写文件的重定向、here-doc、把字交给另一个 shell 或解释器的,都算会改。
 * 按 sh 的写法切开(引号、转义、; && || | & 换行、括号),每一段单独判,全部只读才算只读。
 */
object RootCommandClass {
    enum class Kind { ReadOnly, Changes }

    /** 免确认命令运行时也用这个 PATH，保证裸命令名不会解析到用户目录。 */
    const val SYSTEM_COMMAND_PATH = "/system/bin"

    fun classify(command: String): Kind {
        val commands = split(command) ?: return Kind.Changes
        if (commands.isEmpty()) return Kind.Changes
        return if (commands.all(::readOnly)) Kind.ReadOnly else Kind.Changes
    }

    /** 只看、不改的命令(参数怎么写都不会写东西的)。 */
    private val READERS = setOf(
        "ls", "cat", "head", "tail", "wc", "stat", "du", "df", "grep", "egrep", "fgrep", "id", "whoami", "uname", "getprop",
        "ps", "pwd", "echo", "printf", "file", "md5sum", "sha1sum", "sha256sum", "sha512sum", "readlink", "realpath",
        "basename", "dirname", "test", "[", "true", "false", "which", "uptime", "free", "cut", "tr", "strings",
        "od", "hexdump", "cmp", "diff", "base64", "getenforce", "lsof", "netstat", "nproc", "printenv", "cd",
        "sleep", "lsattr", "nl", "column", "comm", "expr", "seq", "rev", "fold", "tac",
    )

    /** dumpsys 里会改状态的子命令(如 `dumpsys battery set`)。 */
    private val DUMPSYS_WRITES = setOf("set", "reset", "unplug", "force-idle", "unforce", "enable", "disable", "step", "whitelist", "tempwhitelist", "put", "delete", "clear")

    /** `pm`、`cmd package` 里只读的子命令。 */
    private val PM_READ = setOf(
        "list", "path", "dump", "resolve-activity", "query-activities", "query-services", "query-receivers",
        "get-install-location", "get-max-users", "get-max-running-users", "has-feature", "get-app-links", "get-privapp-permissions",
    )

    /** sqlite3 里只读的 PRAGMA(不带 = 的读法)。 */
    private val PRAGMA_READ = setOf(
        "table_info", "table_xinfo", "table_list", "index_list", "index_info", "index_xinfo", "foreign_key_list", "database_list",
        "user_version", "schema_version", "application_id", "page_count", "page_size", "integrity_check", "quick_check",
        "compile_options", "encoding", "journal_mode", "function_list", "collation_list", "freelist_count",
    )

    /** sqlite3 的点命令里只读的。 */
    private val SQLITE_DOT_READ = setOf(".tables", ".schema", ".indexes", ".indices", ".headers", ".mode", ".dump", ".databases", ".fullschema", ".show", ".width", ".nullvalue", ".separator")

    private val SQLITE_FLAGS = setOf(
        "-header", "-noheader", "-csv", "-json", "-line", "-list", "-column", "-html", "-box", "-table", "-markdown", "-readonly",
        "-batch", "-bail", "-echo", "-quote", "-tabs", "-ascii",
    )
    private val SQLITE_OPTS_WITH_VALUE = setOf("-separator", "-newline", "-nullvalue")

    private fun readOnly(words: List<String>): Boolean {
        val assignments = words.takeWhile(ASSIGNMENT::matches)
        // PATH、LD_PRELOAD 等变量可以改变实际运行的程序。只允许常见的语言设置。
        if (assignments.any { it.substringBefore('=') !in LOCALE_ASSIGNMENTS }) return false
        val rest = words.drop(assignments.size)
        if (rest.isEmpty()) return true
        var name = rest[0]
        var args = rest.drop(1)
        // 命令名是变量、带引号拼出来的:看不清
        if ('$' in name) return false
        name = name.removePrefix("$SYSTEM_COMMAND_PATH/")
        if ('/' in name) return false
        if ((name == "toybox" || name == "busybox") && args.isNotEmpty()) {
            name = args[0]
            args = args.drop(1)
        }
        return when (name) {
            in READERS -> true
            "dumpsys" -> args.none { it in DUMPSYS_WRITES }
            // date -s 改时间;sort -o 写文件;uniq 的第二个文件是输出
            "date" -> args.none { it.startsWith("-s") || it.startsWith("--set") }
            "sort" -> args.none { (it.startsWith("-") && !it.startsWith("--") && 'o' in it) || it.startsWith("--output") }
            "uniq" -> args.count { !it.startsWith("-") } <= 1
            "find" -> args.none { it in FIND_WRITES }
            // sed 脚本可写文件、执行子命令,-f 还会读另一份脚本;不做不完整的事后猜测,一律走确认
            "logcat" -> logcatReadOnly(args)
            "dmesg" -> args.none { it in DMESG_WRITES }
            "mount" -> args.isEmpty()
            "env" -> args.isEmpty()
            "pm" -> args.firstOrNull() in PM_READ
            "cmd" -> args.firstOrNull() == "package" && args.getOrNull(1) in PM_READ
            "settings" -> args.dropWhile { it.startsWith("--") || it.all(Char::isDigit) }.firstOrNull().let { it == "get" || it == "list" }
            "sqlite3" -> sqliteReadOnly(args)
            else -> false
        }
    }

    private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
    private val LOCALE_ASSIGNMENTS = setOf("LANG", "LC_ALL")
    private val FIND_WRITES = setOf("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls")
    private val LOGCAT_READ_FLAGS = setOf("-d", "-D", "-h", "-L", "-g", "-S", "-p", "-s", "-B", "--help", "--last", "--statistics")
    private val LOGCAT_READ_LONG_VALUES = setOf("--buffer", "--format", "--max-count", "--pid", "--regex", "--tail", "--uid")
    private val LOGCAT_READ_VALUES = LOGCAT_READ_LONG_VALUES + setOf("-b", "-e", "-m", "-t", "-T", "-v")
    private val LOGCAT_FILTER = Regex("[A-Za-z0-9_.][A-Za-z0-9_.:-]*")
    private val DMESG_WRITES = setOf("-c", "-C", "-n", "--clear", "--console-level", "--read-clear")

    /** 短选项可组合，参数也可紧跟选项。只接受明确列出的只读写法，其他形式走确认。 */
    private fun logcatReadOnly(args: List<String>): Boolean {
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            when {
                arg == "--" -> return true // 后面是过滤表达式，getopt 不再解析选项。
                arg in LOGCAT_READ_FLAGS || LOGCAT_FILTER.matches(arg) -> Unit
                arg in LOGCAT_READ_VALUES -> {
                    val value = args.getOrNull(i++) ?: return false
                    // 参数展开成多个词时，额外的词可能变成新选项。
                    if (value.isEmpty() || value.any { it in "*?[{" }) return false
                }
                '=' in arg && arg.substringBefore('=') in LOGCAT_READ_LONG_VALUES -> {
                    if (arg.substringAfter('=').any { it in "*?[{" }) return false
                }
                else -> return false
            }
        }
        return true
    }

    /** sqlite3 [选项] 库文件 SQL…:SQL 每一句都只读才算。没给 SQL(会从标准输入读)、不认识的选项都算会改。 */
    private fun sqliteReadOnly(args: List<String>): Boolean {
        val sql = mutableListOf<String>()
        var db: String? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                db == null && a in SQLITE_FLAGS -> Unit
                db == null && a in SQLITE_OPTS_WITH_VALUE -> i++
                db == null && a == "-cmd" -> { sql += args.getOrNull(i + 1) ?: return false; i++ }
                db == null && a.startsWith("-") -> return false
                db == null -> db = a
                else -> sql += a
            }
            i++
        }
        if (db == null || sql.isEmpty()) return false
        val statements = sql.flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }
        return statements.isNotEmpty() && statements.all(::sqlReadOnly)
    }

    private fun sqlReadOnly(s: String): Boolean {
        val lower = s.lowercase()
        if (lower.startsWith(".")) return lower.split(Regex("\\s+")).first() in SQLITE_DOT_READ
        val first = lower.split(Regex("[\\s(]+")).first()
        return when (first) {
            "select", "explain" -> WRITE_SQL.none { Regex("\\b$it\\b").containsMatchIn(lower) }
            "pragma" -> '=' !in lower && lower.removePrefix("pragma").trim().substringBefore('(').trim().substringAfter('.') in PRAGMA_READ
            else -> false
        }
    }

    /** 混在 SELECT 里也算会改的字(子句里藏着的写语句、加载扩展)。 */
    private val WRITE_SQL = listOf("insert", "update", "delete", "replace", "drop", "create", "alter", "attach", "detach", "vacuum", "reindex", "load_extension", "writefile", "edit")

    /**
     * 按 sh 的写法切成一段段简单命令(每段是去掉引号后的词)。遇到看不清的写法返回 null(当作会改):
     * 命令替换、进程替换、here-doc、读写打开、写到 /dev/null 以外的重定向、没收尾的引号。
     */
    private fun split(command: String): List<List<String>>? {
        val commands = mutableListOf<List<String>>()
        var words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var single = false
        var double = false
        var i = 0
        fun endWord() {
            if (inWord) words += word.toString()
            word.clear()
            inWord = false
        }
        fun endCommand() {
            endWord()
            if (words.isNotEmpty()) commands += words
            words = mutableListOf()
        }
        while (i < command.length) {
            val c = command[i]
            val next = command.getOrNull(i + 1)
            when {
                single -> if (c == '\'') single = false else word.append(c)
                c == '\\' && next != null -> { word.append(next); inWord = true; i++ }
                c == '`' -> return null
                // 任何未转义且不在单引号里的 $ 都留到 root shell 展开,静态分类看不见展开后的参数
                c == '$' -> return null
                double -> when (c) {
                    '"' -> double = false
                    else -> word.append(c)
                }
                c == '\'' -> { single = true; inWord = true }
                c == '"' -> { double = true; inWord = true }
                c == '>' || (c == '&' && next == '>') -> {
                    // 前面紧挨着的数字是文件描述符(2>…)
                    if (inWord && word.all(Char::isDigit)) { word.clear(); inWord = false } else endWord()
                    i += if (c == '&') 2 else 1
                    if (command.getOrNull(i) == '>' || command.getOrNull(i) == '|') i++
                    // >&2、>&- 是复制、关闭描述符,不写文件
                    if (command.getOrNull(i) == '&') {
                        i++
                        val fd = command.drop(i).takeWhile { it.isDigit() || it == '-' }
                        if (fd.isEmpty()) return null
                        i += fd.length
                        continue
                    }
                    while (command.getOrNull(i)?.isWhitespace() == true) i++
                    val target = command.drop(i).takeWhile { !it.isWhitespace() && it !in ";&|()<>" }
                    if (target != "/dev/null") return null
                    i += target.length
                    continue
                }
                c == '<' -> {
                    if (inWord && word.all(Char::isDigit)) { word.clear(); inWord = false } else endWord()
                    // here-doc、读写打开、进程替换
                    if (next == '<' || next == '>' || next == '(') return null
                    i++
                    while (command.getOrNull(i)?.isWhitespace() == true) i++
                    val target = command.drop(i).takeWhile { !it.isWhitespace() && it !in ";&|()<>" }
                    if (target.isEmpty() || '$' in target) return null
                    i += target.length
                    continue
                }
                c in ";&|\n()" -> endCommand()
                c.isWhitespace() -> endWord()
                else -> { word.append(c); inWord = true }
            }
            i++
        }
        if (single || double) return null
        endCommand()
        // { 和 } 只是把几条括起来
        return commands.map { w -> w.filter { it != "{" && it != "}" } }.filter { it.isNotEmpty() }
    }
}
