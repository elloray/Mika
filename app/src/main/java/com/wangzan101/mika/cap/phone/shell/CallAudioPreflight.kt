package com.wangzan101.mika.cap.phone.shell

import android.os.Looper
import android.os.Process
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Read-only diagnostics; no recorder, track, call, role, or app-op is opened or changed.
 * From an authorized adb shell:
 * CLASSPATH=<Mika base.apk> app_process /system/bin com.wangzan101.mika.cap.phone.shell.CallAudioPreflight
 */
@Keep
object CallAudioPreflight {
    @JvmStatic
    @Suppress("DEPRECATION") // app_process has no Application/main looper until we initialize it.
    fun main(args: Array<String>) {
        if (Process.myUid() != ShellContext.SHELL_UID) {
            System.err.println("Run as Android shell UID 2000.")
            exitProcess(2)
        }
        Looper.prepareMainLooper()
        val result = ShellCallAudio(ShellContext.create()).probe(ShellCallAudio.AUTO)
        println(listOf("uid", "sdk", "direct", "redirect", "telephonyRx", "telephonyTx", "ready", "verified", "reason")
            .joinToString("\n") { key -> "$key=${result.get(key)}" })
        System.out.flush()
        // ActivityThread initialization can keep framework worker threads alive after main returns.
        exitProcess(0)
    }
}
