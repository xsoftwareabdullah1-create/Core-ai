package ai.ultron.mobile
import android.content.Context
import java.io.File
class WorkspaceTools(context: Context) {
    private val root = File(context.filesDir, "ultron-workspace").apply { mkdirs() }
    fun list(): String = root.walkTopDown().filter { it.isFile }.take(300).joinToString("\n") { it.relativeTo(root).path }.ifBlank { "(workspace empty)" }
    fun read(path: String): String {
        val f = resolve(path); require(f.isFile) { "File not found" }; require(f.length() <= 1_000_000) { "File exceeds 1 MB" }
        return f.readText()
    }
    fun write(path: String, content: String): String {
        require(content.length <= 1_000_000) { "File exceeds 1 MB" }
        val f = resolve(path); f.parentFile?.mkdirs(); f.writeText(content); return "Saved " + f.relativeTo(root).path + " (" + f.length() + " bytes)"
    }
    fun delete(path: String): String {
        val f = resolve(path); require(f.isFile) { "Only files can be deleted" }
        return if (f.delete()) "Deleted $path" else "Delete failed"
    }
    private fun resolve(path: String): File {
        require(path.isNotBlank() && !path.startsWith("/")) { "Use a relative path" }
        val f = File(root, path).canonicalFile
        require(f.path.startsWith(root.canonicalPath + File.separator)) { "Path escapes ULTRON workspace" }
        return f
    }
}
