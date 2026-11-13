package ai.ultron.mobile

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AgentAction(val id: String, val name: String, val arguments: JSONObject) {
    fun description(): String = when (name) {
        "workspace_write" -> "Create or overwrite workspace file: " + arguments.optString("path")
        "workspace_delete" -> "Delete workspace file: " + arguments.optString("path")
        "ui_tap_text" -> "Tap visible UI element: " + arguments.optString("text")
        "ui_global_action" -> "Perform phone navigation: " + arguments.optString("action")
        "app_launch" -> "Launch Android app package: " + arguments.optString("package_name")
        "apk_build_start" -> "Upload/build this Android project through GitHub Actions"
        else -> "ULTRON wants to run: $name"
    }
}

class OpenRouterAgent(
    private val context: Context,
    private val apiKey: String,
    private val model: String,
    private val workspace: WorkspaceTools,
    private val github: GitHubBuildClient
) {
    private fun tools(): JSONArray {
        val specs = listOf(
            Triple("workspace_list", "List files in ULTRON's private project workspace.", """{"type":"object","properties":{} }"""),
            Triple("workspace_read", "Read a text file from the private workspace.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
            Triple("workspace_write", "Create or overwrite a text file in the private workspace.", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}"""),
            Triple("workspace_delete", "Delete a file in the private workspace.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
            Triple("ui_read_visible", "Read text exposed by Android Accessibility from the current screen.", """{"type":"object","properties":{} }"""),
            Triple("ui_tap_text", "Tap an exact visible label using Android Accessibility.", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""),
            Triple("ui_global_action", "Use Android Back, Home, Recents or Notifications.", """{"type":"object","properties":{"action":{"type":"string","enum":["back","home","recents","notifications"]}},"required":["action"]}"""),
            Triple("app_launch", "Launch an installed Android app by package name.", """{"type":"object","properties":{"package_name":{"type":"string"}},"required":["package_name"]}"""),
            Triple("apk_build_start", "Trigger the configured GitHub Actions Android APK build.", """{"type":"object","properties":{} }"""),
            Triple("apk_build_status", "Check the latest GitHub Actions build status and artifact.", """{"type":"object","properties":{} }""")
        )
        val arr = JSONArray()
        for ((name, description, schema) in specs) {
            arr.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", name).put("description", description).put("parameters", JSONObject(schema))))
        }
        return arr
    }

    suspend fun run(
        history: List<Pair<String, String>>,
        prompt: String,
        approve: suspend (AgentAction) -> Boolean,
        report: (String) -> Unit
    ): String {
        require(apiKey.isNotBlank()) { "Set your OpenRouter API key in Settings." }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", """
You are ULTRON, an autonomous Android coding agent. Use available tools to work, not just describe.
All tool actions are subject to a user approval dialog. Never claim an action succeeded before its tool result confirms it.
Use workspace files for project code. Keep paths relative, do not attempt to escape the workspace. Before deleting or overwriting, explain why. For phone control, inspect visible UI first, then ask for the smallest necessary action. Do not bypass Android permissions, lock screens, payment/security prompts, or OTPs. For APK builds, use the connected GitHub Actions workflow. Explain build status and artifact link only when the tool returns them.
""".trimIndent()))
        history.takeLast(12).forEach { (role, text) -> messages.put(JSONObject().put("role", role).put("content", text)) }
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        repeat(7) {
            val response = request(messages)
            val choice = response.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return message.optString("content", "Done.")
            messages.put(message)
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val name = fn.getString("name")
                val args = JSONObject(fn.optString("arguments", "{}"))
                val action = AgentAction(call.optString("id"), name, args)
                report(action.description())
                val result = if (approve(action)) {
                    try { execute(name, args) } catch (e: Exception) { "Tool failed: ${e.message}" }
                } else "User denied this action. Do not retry it unless the user asks."
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", action.id).put("content", result))
            }
        }
        return "I reached the tool-call limit for this request. Tell me what to continue with."
    }

    private suspend fun request(messages: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 90000; doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("HTTP-Referer", "https://github.com/xsoftwareabdullah1-create/Core-ai")
            setRequestProperty("X-Title", "ULTRON AI")
        }
        try {
            val body = JSONObject().put("model", model).put("messages", messages).put("tools", tools()).put("tool_choice", "auto").put("temperature", 0.2)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("OpenRouter HTTP $code: $text")
            JSONObject(text)
        } finally { conn.disconnect() }
    }

    private suspend fun execute(name: String, a: JSONObject): String = when (name) {
        "workspace_list" -> workspace.list()
        "workspace_read" -> workspace.read(a.getString("path"))
        "workspace_write" -> workspace.write(a.getString("path"), a.getString("content"))
        "workspace_delete" -> workspace.delete(a.getString("path"))
        "ui_read_visible" -> UltronAccessibilityService.current?.visibleText() ?: "Accessibility service is not enabled. Enable ULTRON Screen Control in Android Accessibility settings."
        "ui_tap_text" -> {
            val svc = UltronAccessibilityService.current ?: error("Enable ULTRON Screen Control in Android Accessibility settings.")
            if (svc.tapText(a.getString("text"))) "Tap action sent." else "No matching clickable text was found."
        }
        "ui_global_action" -> {
            val svc = UltronAccessibilityService.current ?: error("Enable ULTRON Screen Control first.")
            if (svc.global(a.getString("action"))) "Navigation action sent." else "Android rejected this action."
        }
        "app_launch" -> {
            val pkg = a.getString("package_name")
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: error("App not found: $pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); "Launch intent sent for $pkg."
        }
        "apk_build_start" -> github.dispatch()
        "apk_build_status" -> {
            val b = github.latest()
            "Build #${b.runId}: ${b.status} / ${b.conclusion}. Run: ${b.runUrl}. Artifact API: ${b.artifactUrl.ifBlank { "not uploaded yet" }}"
        }
        else -> "Unknown tool: $name"
    }
}
