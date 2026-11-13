package ai.ultron.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Deep = Color(0xFF070B14)
private val SurfaceDark = Color(0xFF111C2B)
private val Neon = Color(0xFF45D9FF)
private val Subtle = Color(0xFF9BB0C7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UltronHome(
                openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )
        }
    }
}

private data class ChatLine(val user: Boolean, val text: String)

@Composable
private fun UltronHome(openAccessibility: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SecureStore(context) }
    var apiKey by remember { mutableStateOf(store.get("openrouter_key")) }
    var model by remember { mutableStateOf(store.get("model").ifBlank { "openai/gpt-4o-mini" }) }
    var githubToken by remember { mutableStateOf(store.get("github_token")) }
    var repo by remember { mutableStateOf(store.get("github_repo").ifBlank { "xsoftwareabdullah1-create/Core-ai" }) }
    var settingsOpen by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("SYSTEM READY") }
    var pending by remember { mutableStateOf<AgentAction?>(null) }
    var approvalSignal by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var buildInfo by remember { mutableStateOf<BuildInfo?>(null) }
    var buildError by remember { mutableStateOf("") }
    val chat = remember { mutableStateListOf(ChatLine(false, "ULTRON online. I can write project files, inspect your screen, control approved UI actions and trigger Android APK builds.")) }
    val workspace = remember { WorkspaceTools(context) }
    val voice = rememberUltronVoiceInput { prompt = it }

    suspend fun refreshBuild() {
        try {
            buildError = ""
            buildInfo = GitHubBuildClient(githubToken, repo).latest()
        } catch (e: Exception) { buildError = e.message ?: "Could not load build status." }
    }

    LaunchedEffect(buildInfo?.status, buildInfo?.conclusion) {
        if (buildInfo?.status == "in_progress" || buildInfo?.status == "queued") {
            delay(6000)
            refreshBuild()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Neon, background = Deep, surface = SurfaceDark)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10233A), Deep, Color(0xFF05080D))))) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("ULTRON AI", color = Color.White, fontSize = 26.sp)
                        Text("PERSONAL AI • DEVICE CONTROL", color = Neon, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, null, tint = Neon) }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(20.dp)).border(1.dp, Neon.copy(alpha = .35f), RoundedCornerShape(20.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize().border(1.dp, Neon.copy(alpha = .28f), CircleShape).padding(6.dp).border(1.dp, Neon.copy(alpha = .55f), CircleShape).padding(5.dp).border(2.dp, Neon.copy(alpha = .8f), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(34.dp).background(Neon.copy(alpha = .12f), CircleShape).border(1.dp, Neon, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Memory, null, tint = Neon, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(status, color = Neon, fontSize = 11.sp, letterSpacing = 1.5.sp)
                        Text("OpenRouter • $model", color = Subtle, fontSize = 10.sp)
                        Text(if (UltronAccessibilityService.current != null) "Accessibility connected" else "Accessibility not enabled", color = if (UltronAccessibilityService.current != null) Neon else Subtle, fontSize = 10.sp)
                    }
                    IconButton(onClick = openAccessibility) { Icon(Icons.Default.PhonelinkSetup, null, tint = Neon) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    MiniAction("Voice", Icons.Default.Mic, Modifier.weight(1f), voice)
                    MiniAction("Read screen", Icons.Default.Visibility, Modifier.weight(1f)) {
                        val text = UltronAccessibilityService.current?.visibleText() ?: "Enable ULTRON Accessibility first."
                        chat.add(ChatLine(false, text))
                    }
                    MiniAction("Build monitor", Icons.Default.Build, Modifier.weight(1f)) { scope.launch { refreshBuild() } }
                }
                buildInfo?.let { info ->
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(14.dp)).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("APK BUILD #${info.runId}", color = Neon, fontSize = 10.sp)
                            Spacer(Modifier.weight(1f))
                            Text("${info.status.uppercase()} ${info.conclusion.uppercase()}", color = Color.White, fontSize = 10.sp)
                        }
                        Text(info.runUrl, color = Subtle, fontSize = 9.sp)
                        if (info.artifactUrl.isNotBlank()) Text("APK artifact ready — open the run page to download.", color = Neon, fontSize = 10.sp)
                    }
                }
                if (buildError.isNotBlank()) Text(buildError, color = Color(0xFFFF9B9B), fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))
                Text("AGENT CONSOLE", color = Subtle, fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(5.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chat) { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.user) Arrangement.End else Arrangement.Start) {
                            Column(Modifier.widthIn(max = 330.dp).background(if (line.user) Color(0xFF12334A) else SurfaceDark, RoundedCornerShape(15.dp)).border(1.dp, if (line.user) Neon.copy(alpha = .25f) else Color.White.copy(alpha = .05f), RoundedCornerShape(15.dp)).padding(11.dp)) {
                                Text(if (line.user) "YOU" else "ULTRON", color = Neon, fontSize = 9.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(line.text, color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(17.dp)).border(1.dp, Neon.copy(alpha = .35f), RoundedCornerShape(17.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = prompt, onValueChange = { prompt = it }, modifier = Modifier.weight(1f).padding(9.dp),
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp), cursorBrush = Brush.linearGradient(listOf(Neon, Color.White)),
                        decorationBox = { inner -> if (prompt.isEmpty()) Text("Tell ULTRON what to build…", color = Subtle, fontSize = 12.sp); inner() }
                    )
                    IconButton(enabled = !busy && prompt.isNotBlank(), onClick = {
                        val userPrompt = prompt.trim(); prompt = ""
                        val previous = chat.map { (if (it.user) "user" else "assistant") to it.text }
                        chat.add(ChatLine(true, userPrompt)); busy = true; status = "AGENT WORKING"
                        scope.launch {
                            try {
                                val engine = OpenRouterAgent(context, apiKey, model, workspace, GitHubBuildClient(githubToken, repo))
                                val reply = engine.run(previous, userPrompt, approve = { action ->
                                    val signal = CompletableDeferred<Boolean>()
                                    pending = action; approvalSignal = signal
                                    val accepted = signal.await()
                                    pending = null; approvalSignal = null
                                    accepted
                                }, report = { status = "TOOL: ${it.take(38)}" })
                                chat.add(ChatLine(false, reply))
                                status = "SYSTEM READY"
                            } catch (e: Exception) {
                                chat.add(ChatLine(false, "Agent error: ${e.message ?: "Unknown error"}"))
                                status = "NEEDS ATTENTION"
                            } finally { busy = false }
                        }
                    }) {
                        if (busy) CircularProgressIndicator(Modifier.size(21.dp), color = Neon, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Send, null, tint = Neon)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Every phone, file and build action waits for your approval.", color = Subtle, fontSize = 9.sp)
            }
        }

        if (settingsOpen) {
            AlertDialog(
                onDismissRequest = { settingsOpen = false }, containerColor = SurfaceDark,
                title = { Text("ULTRON SETTINGS", color = Neon) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("OpenRouter API key", color = Color.White, fontSize = 11.sp)
                        OutlinedTextField(apiKey, { apiKey = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = { Text("sk-or-…") })
                        Text("Model ID", color = Color.White, fontSize = 11.sp)
                        OutlinedTextField(model, { model = it }, singleLine = true)
                        Text("GitHub token (Actions write + Contents read)", color = Color.White, fontSize = 11.sp)
                        OutlinedTextField(githubToken, { githubToken = it }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        Text("Repository owner/name", color = Color.White, fontSize = 11.sp)
                        OutlinedTextField(repo, { repo = it }, singleLine = true)
                        Text("Credentials are stored encrypted on this device. Never paste them into chat or commit them.", color = Subtle, fontSize = 9.sp)
                        OutlinedButton(onClick = openAccessibility, modifier = Modifier.fillMaxWidth()) { Text("ENABLE ACCESSIBILITY CONTROL") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        store.put("openrouter_key", apiKey.trim()); store.put("model", model.trim())
                        store.put("github_token", githubToken.trim()); store.put("github_repo", repo.trim())
                        settingsOpen = false
                    }) { Text("SAVE", color = Neon) }
                },
                dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text("CANCEL", color = Subtle) } }
            )
        }

        pending?.let { action ->
            AlertDialog(
                onDismissRequest = { approvalSignal?.complete(false) },
                containerColor = SurfaceDark,
                title = { Text("APPROVE ACTION?", color = Neon) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(action.description(), color = Color.White)
                        Text("Tool: ${action.name}", color = Subtle, fontSize = 10.sp)
                        Text(action.arguments.toString(), color = Subtle, fontSize = 9.sp)
                        Text("Only approve if you understand and want this action.", color = Color(0xFFFFD38A), fontSize = 10.sp)
                    }
                },
                confirmButton = { TextButton(onClick = { approvalSignal?.complete(true) }) { Text("APPROVE", color = Neon) } },
                dismissButton = { TextButton(onClick = { approvalSignal?.complete(false) }) { Text("DENY", color = Color(0xFFFF9B9B)) } }
            )
        }
    }
}

@Composable
private fun MiniAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, click: () -> Unit) {
    OutlinedButton(onClick = click, modifier = modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 4.dp), shape = RoundedCornerShape(11.dp)) {
        Icon(icon, null, tint = Neon, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(label, color = Color.White, fontSize = 9.sp)
    }
}
