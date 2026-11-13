package ai.ultron.mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
data class BuildInfo(val status: String, val conclusion: String, val runUrl: String, val runId: Long, val artifactUrl: String)
class GitHubBuildClient(private val token: String, private val repo: String) {
    private fun request(method: String, path: String, body: String? = null): Pair<Int, String> {
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Repository must be owner/name" }
        val c = (URL("https://api.github.com/repos/$repo/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 25000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }
    suspend fun dispatch(): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Add a GitHub token with Actions write permission." }
        val (code, body) = request("POST", "actions/workflows/android.yml/dispatches", JSONObject().put("ref", "main").toString())
        if (code != 204) error("GitHub dispatch HTTP $code: $body")
        "Build queued successfully on $repo. Open Build Monitor to watch the run."
    }
    suspend fun latest(): BuildInfo = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Add a GitHub token first." }
        val (code, body) = request("GET", "actions/runs?branch=main&per_page=1")
        if (code !in 200..299) error("GitHub status HTTP $code: $body")
        val runs = JSONObject(body).getJSONArray("workflow_runs")
        if (runs.length() == 0) return@withContext BuildInfo("no runs", "", "", 0, "")
        val r = runs.getJSONObject(0); val id = r.getLong("id")
        val (aCode, aBody) = request("GET", "actions/runs/$id/artifacts")
        var artifact = ""
        if (aCode in 200..299) {
            val arr = JSONObject(aBody).getJSONArray("artifacts")
            if (arr.length() > 0) artifact = arr.getJSONObject(0).optString("archive_download_url")
        }
        BuildInfo(r.optString("status"), r.optString("conclusion"), r.optString("html_url"), id, artifact)
    }
}
