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
        "ui_tap_coordinates" -> "Tap screen at ${arguments.optInt("x")}, ${arguments.optInt("y")}"
        "ui_swipe" -> "Swipe screen in the requested direction"
        "ui_type_text" -> "Type text into the focused input field"
        "ui_scroll" -> "Scroll screen: " + arguments.optString("direction")
        "ui_global_action" -> "Perform phone navigation: " + arguments.optString("action")
        "app_launch" -> "Launch Android app package: " + arguments.optString("package_name")
        "apk_build_start" -> "Build Android APK through GitHub Actions"
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
            Triple("ui_read_visible", "Read visible labels and screen coordinates from the foreground app. Do this before interacting.", """{"type":"object","properties":{} }"""),
            Triple("ui_tap_text", "Tap a visible UI control by its exact label.", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""),
            Triple("ui_tap_coordinates", "Tap the screen at absolute pixel coordinates obtained from ui_read_visible.", """{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}"""),
            Triple("ui_swipe", "Swipe between absolute pixel coordinates.", """{"type":"object","properties":{"start_x":{"type":"integer"},"start_y":{"type":"integer"},"end_x":{"type":"integer"},"end_y":{"type":"integer"},"duration_ms":{"type":"integer"}},"required":["start_x","start_y","end_x","end_y"]}"""),
            Triple("ui_scroll", "Scroll foreground content up, down, left or right.", """{"type":"object","properties":{"direction":{"type":"string","enum":["up","down","left","right"]}},"required":["direction"]}"""),
            Triple("ui_type_text", "Enter text into the currently focused editable field. Never use for passwords, OTPs or payment credentials.", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""),
            Triple("ui_global_action", "Use Android Back, Home, Recents, Notifications or Quick Settings.", """{"type":"object","properties":{"action":{"type":"string","enum":["back","home","recents","notifications","quick_settings"]}},"required":["action"]}"""),
            Triple("app_launch", "Launch an installed Android app by package name.", """{"type":"object","properties":{"package_name":{"type":"string"}},"required":["package_name"]}"""),
            Triple("apk_build_start", "Trigger the configured GitHub Actions Android APK build.", """{"type":"object","properties":{} }"""),
            Triple("apk_build_status", "Check the latest GitHub Actions build status and artifact.", """{"type":"object","properties":{} }""")
        )
        val arr = JSONArray()
        for ((name, description, schema) in specs) arr.put(JSONObject().put("type","function").put("function",
            JSONObject().put("name",name).put("description",description).put("parameters",JSONObject(schema))))
        return arr
    }

    suspend fun run(history: List<Pair<String,String>>, prompt: String, approve: suspend (AgentAction)->Boolean, report:(String)->Unit): String {
        require(apiKey.isNotBlank()) { "Set your OpenRouter API key in Settings." }
        report("Native route: ${NativeEngine.classify(prompt)}")
        val pythonRuntime = PythonRuntime(context)
        val messages = JSONArray()
        messages.put(JSONObject().put("role","system").put("content", """
You are ULTRON, an Android coding and device-control agent. Use tools to perform tasks, not merely describe them.
Every action requires explicit user approval. Never claim success without a successful tool result.
For phone control: first call ui_read_visible, interpret the visible labels/coordinates, then perform only the smallest necessary approved action. Re-read the screen after important navigation.
Use accessibility only when the user has enabled ULTRON Screen Control in Android settings. Do not bypass lock screens, security/payment/OTP prompts, passwords, or Android permission dialogs. Never enter passwords, OTPs, banking details or payment credentials.
Keep workspace paths relative. Explain destructive overwrites/deletions. For builds use GitHub Actions and report only verified status.
""".trimIndent()))
        history.takeLast(12).forEach { (role,text)->messages.put(JSONObject().put("role",role).put("content",text)) }
        messages.put(JSONObject().put("role","user").put("content",prompt))
        repeat(7) {
            val response=request(messages); val message=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls=message.optJSONArray("tool_calls")
            if(calls==null||calls.length()==0) return message.optString("content","Done.")
            messages.put(message)
            for(i in 0 until calls.length()){
                val call=calls.getJSONObject(i); val fn=call.getJSONObject("function")
                val name=fn.getString("name"); val args=JSONObject(fn.optString("arguments","{}"))
                val action=AgentAction(call.optString("id"),name,args)
                val validated=pythonRuntime.validatePlan(JSONObject().put("steps",JSONArray().put(JSONObject().put("tool",name).put("arguments",args))).toString())
                require(JSONObject(validated).getJSONArray("steps").getJSONObject(0).getString("tool")==name){"Python runtime rejected tool plan."}
                report(action.description())
                val result=if(approve(action)){try{execute(name,args)}catch(e:Exception){"Tool failed: ${e.message}"}}else "User denied this action. Do not retry unless asked."
                messages.put(JSONObject().put("role","tool").put("tool_call_id",action.id).put("content",result))
            }
        }
        return "I reached the tool-call limit. Tell me what to continue."
    }

    private suspend fun request(messages:JSONArray):JSONObject=withContext(Dispatchers.IO){
        val conn=(URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply{
            requestMethod="POST";connectTimeout=20000;readTimeout=90000;doOutput=true
            setRequestProperty("Authorization","Bearer $apiKey");setRequestProperty("Content-Type","application/json")
            setRequestProperty("HTTP-Referer","https://github.com/xsoftwareabdullah1-create/Core-ai");setRequestProperty("X-Title","ULTRON AI")
        }
        try{
            val body=JSONObject().put("model",model).put("messages",messages).put("tools",tools()).put("tool_choice","auto").put("temperature",0.2)
            conn.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
            val code=conn.responseCode;val stream=if(code in 200..299)conn.inputStream else conn.errorStream
            val raw=stream.bufferedReader().use{it.readText()};if(code !in 200..299)error("OpenRouter HTTP $code: $raw")
            JSONObject(raw)
        }finally{conn.disconnect()}
    }

    private suspend fun execute(name:String,a:JSONObject):String=when(name){
        "workspace_list"->workspace.list()
        "workspace_read"->workspace.read(a.getString("path"))
        "workspace_write"->workspace.write(a.getString("path"),a.getString("content"))
        "workspace_delete"->workspace.delete(a.getString("path"))
        "ui_read_visible"->UltronAccessibilityService.current?.visibleText()?:error("Enable ULTRON Screen Control in Android Accessibility settings.")
        "ui_tap_text"->{val s=service();if(s.tapText(a.getString("text")))"Tap completed." else "No matching clickable label was found."}
        "ui_tap_coordinates"->{val s=service();if(s.tapCoordinates(a.getInt("x").toFloat(),a.getInt("y").toFloat()))"Tap gesture dispatched." else "Android rejected the tap gesture."}
        "ui_swipe"->{val s=service();if(s.swipe(a.getInt("start_x").toFloat(),a.getInt("start_y").toFloat(),a.getInt("end_x").toFloat(),a.getInt("end_y").toFloat(),a.optLong("duration_ms",420)))"Swipe gesture dispatched." else "Android rejected the swipe."}
        "ui_scroll"->{val s=service();if(s.scroll(a.getString("direction")))"Scroll action dispatched." else "No scrollable target or gesture dispatch failed."}
        "ui_type_text"->{val text=a.getString("text");require(text.length<=4000){"Text too long."};if(service().typeText(text))"Text entered into the focused field." else "No focused editable field accepted the text."}
        "ui_global_action"->{val key=a.getString("action");require(key!="lock"&&key!="power_dialog"){"This global action is not exposed to the agent."};if(service().global(key))"System navigation action dispatched." else "Android rejected this action."}
        "app_launch"->{val pkg=a.getString("package_name");val intent=context.packageManager.getLaunchIntentForPackage(pkg)?:error("App not found: $pkg");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(intent);"Launch intent sent for $pkg."}
        "apk_build_start"->github.dispatch()
        "apk_build_status"->{val b=github.latest();"Build #${b.runId}: ${b.status} / ${b.conclusion}. Run: ${b.runUrl}. Artifact: ${b.artifactUrl.ifBlank{"not uploaded yet"}}"}
        else->"Unknown tool: $name"
    }
    private fun service():UltronAccessibilityService=UltronAccessibilityService.current?:error("Accessibility service is OFF. Enable ULTRON Screen Control in Android settings.")
}
