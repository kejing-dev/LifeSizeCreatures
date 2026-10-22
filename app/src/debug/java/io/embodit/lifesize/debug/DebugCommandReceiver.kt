package io.embodit.lifesize.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.lifesize.platform.LifeLog

/**
 * Debug builds only. Forwards adb broadcasts to the app (action `io.embodit.lifesize.DEBUG`); see
 * [DebugCommands] for the command list.
 *
 *   adb -s emulator-5562 shell am broadcast -a io.embodit.lifesize.DEBUG -p io.embodit.lifesize --es cmd room --es s S4
 *
 * The argument may also follow the name inside `cmd` (quote it for adb shell).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0]
        val arg = intent.getStringExtra("s") ?: parts.getOrNull(1)
        LifeLog.i("CMD recv '$cmd' arg='$arg'")
        // broadcasts arrive on the main thread, which is where the store and the scene live
        DebugCommands.run(cmd, arg?.trim())
    }
}
