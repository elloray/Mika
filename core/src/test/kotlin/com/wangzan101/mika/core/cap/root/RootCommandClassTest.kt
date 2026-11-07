package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.cap.root.RootCommandClass.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class RootCommandClassTest {
    private fun readOnly(vararg commands: String) = commands.forEach { assertEquals(it, Kind.ReadOnly, RootCommandClass.classify(it)) }
    private fun changes(vararg commands: String) = commands.forEach { assertEquals(it, Kind.Changes, RootCommandClass.classify(it)) }

    @Test
    fun readingCommandsRunDirectly() = readOnly(
        "ls -la /data/data/com.example.app",
        "cat /data/data/com.example.app/shared_prefs/settings.xml",
        "du -sh /sdcard/* 2>/dev/null",
        "stat /data/system/packages.xml",
        "find /sdcard/Download -name '*.apk' -size +10M",
        "ls /data/app | head -n 20; df -h",
        "id && getprop ro.build.version.release",
        "grep -r 'token' /data/data/com.example.app/shared_prefs 2>&1 | wc -l",
        "/system/bin/ls -l /data/local/tmp",
        "toybox ls /data",
        "LANG=C ls /data",
        "echo \\\$HOME",
        "echo '\$HOME'",
        "pm list packages -3",
        "cmd package list packages",
        "settings get secure enabled_accessibility_services",
        "settings --user 0 list global",
        "dumpsys battery",
        "logcat -d -t 100",
        "mount",
        "date",
        "sort /data/system/packages.list | uniq",
        "echo hi >&2",
        "cat < /data/system/packages.list",
        "{ ls /data; ls /sdcard; }",
        "(cd /data/data && ls)",
    )

    @Test
    fun sqliteQueriesThatOnlyReadRunDirectly() = readOnly(
        "sqlite3 /data/data/com.example.app/databases/notes.db 'SELECT title FROM notes LIMIT 10'",
        "sqlite3 -header -csv /data/data/com.example.app/databases/notes.db \"select count(*) from notes;\"",
        "sqlite3 /data/data/com.example.app/databases/notes.db .tables",
        "sqlite3 notes.db 'PRAGMA table_info(notes)'",
        "sqlite3 notes.db '.schema notes'",
    )

    @Test
    fun writesAndDeletesNeedTheUsersOk() = changes(
        "rm -rf /sdcard/Download/old",
        "mv /sdcard/a /sdcard/b",
        "cp /data/data/com.example.app/databases/a.db /sdcard/",
        "echo 1 > /sys/class/leds/x/brightness",
        "cat a >> /sdcard/log.txt",
        "ls /data | tee /sdcard/list.txt",
        "pm uninstall com.example.app",
        "pm clear com.example.app",
        "cmd package uninstall com.example.app",
        "settings put global adb_enabled 0",
        "dd if=/dev/zero of=/dev/block/sda bs=1M count=1",
        "mount -o remount,rw /system",
        "chmod 777 /data/local/tmp/x",
        "setprop persist.sys.x 1",
        "am force-stop com.example.app",
        "reboot",
        "find /sdcard -name '*.tmp' -delete",
        "find /sdcard -name '*.tmp' -exec rm {} \\;",
        "sed -i 's/a/b/' /data/system/x.xml",
        // sed 无论表面是否只读都走确认:脚本能写文件或执行子命令,不能靠事后正则猜透
        "sed -n '1,20p' /data/system/packages.list",
        "sed '1w /data/local/tmp/copied' /data/system/packages.list",
        "sed '1e id' /data/system/packages.list",
        "sed -f /data/local/tmp/script /data/system/packages.list",
        "logcat -c",
        "dmesg -c",
        "date -s 20260101",
        "sort -o /sdcard/out.txt in.txt",
        "uniq in.txt out.txt",
        "dumpsys battery set level 5",
        "ls /data; rm /data/local/tmp/x",
        "ls /data && touch /data/local/tmp/x",
    )

    @Test
    fun unknownExecutablePathsAndEnvironmentOverridesNeedApproval() = changes(
        "/data/local/tmp/cat /data/local/tmp/input",
        "./ls /data",
        "/system/bin/../../data/local/tmp/cat",
        "/data/local/tmp/toybox ls /data",
        "toybox /data/local/tmp/cat",
        "PATH=/data/local/tmp cat /data/local/tmp/input",
        "PATH=/data/local/tmp; cat /data/local/tmp/input",
        "LD_PRELOAD=/data/local/tmp/custom.so ls /data",
    )

    @Test
    fun sqliteWritesNeedTheUsersOk() = changes(
        "sqlite3 notes.db 'DELETE FROM notes'",
        "sqlite3 notes.db \"UPDATE notes SET title='x'\"",
        "sqlite3 notes.db 'select 1; drop table notes'",
        "sqlite3 notes.db 'PRAGMA journal_mode=WAL'",
        "sqlite3 notes.db 'PRAGMA wal_checkpoint'",
        "sqlite3 notes.db",
        "sqlite3 notes.db '.output /sdcard/x.txt'",
        "sqlite3 notes.db 'WITH x AS (SELECT 1) DELETE FROM notes'",
        "sqlite3 -cmd '.shell rm x' notes.db 'select 1'",
        "sqlite3 -unknownflag notes.db 'select 1'",
    )

    @Test
    fun logcatClearAndFileOutputNeedApprovalInAllOptionForms() = changes(
        "logcat -dc",
        "logcat -d -f/data/local/tmp/review.log",
        "logcat -df/data/local/tmp/review.log",
        "logcat -G16M",
        "logcat --file=/data/local/tmp/review.log",
        "logcat -d *",
    )

    @Test
    fun logcatReadOptionsRemainAvailableWithoutApproval() = readOnly(
        "logcat -d -t 100",
        "logcat -d --pid=123 -b main ActivityManager:I",
        "logcat -d -- '*:S'",
    )

    /** 拿不准的一律当作会改:命令替换、交给另一个 shell、变量当命令、没收尾的引号、here-doc、不认识的命令。 */
    @Test
    fun anythingUnclearNeedsTheUsersOk() = changes(
        "ls $(cat /sdcard/list)",
        "ls `cat /sdcard/list`",
        "X=-i; sed \$X 's/a/b/' /data/local/tmp/config",
        "X=-c; logcat \$X",
        "find /data \$ACTION",
        "echo \"\$HOME\"",
        "echo 'rm -rf /sdcard' | sh",
        "sh -c 'ls /data'",
        "\$CMD /data",
        "eval ls",
        "xargs rm < list",
        "awk '{print}' /data/x",
        "ls '/data",
        "cat <<EOF\nhi\nEOF",
        "busybox",
        "some_unknown_tool --flag",
        "env rm x",
        "ls > /sdcard/out.txt",
        "ls 2> /sdcard/err.txt",
        "",
        "   ",
    )

    /** 引号里的分号、管道、大于号是字,不是 shell 的写法。 */
    @Test
    fun quotedOperatorsAreJustText() = readOnly(
        "grep 'a;b|c>d' /data/x",
        "echo \"rm -rf / > x\"",
        "grep -e 'x' -e \"y && z\" /data/x",
    )
}
