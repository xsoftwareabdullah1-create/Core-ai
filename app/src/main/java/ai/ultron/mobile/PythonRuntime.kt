package ai.ultron.mobile

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class PythonRuntime(private val context: Context) {
    @Synchronized
    private fun instance(): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }
    fun validatePlan(json: String): String =
        instance().getModule("ultron_runtime").callAttr("validate_plan", json).toString()
    fun runtimeInfo(): String =
        instance().getModule("ultron_runtime").callAttr("runtime_info").toString()
}
