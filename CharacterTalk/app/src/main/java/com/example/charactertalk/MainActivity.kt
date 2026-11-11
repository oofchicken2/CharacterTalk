package com.example.charactertalk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import java.util.Locale

data class CharacterProfile(
    val id: Long, val name: String, val description: String,
    val personality: String, val scenario: String
)
data class ChatMessage(val role: String, val content: String)

class Vault(private val context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val alias = "charactertalk_key"
    private fun key(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(alias, null)?.let { return it }
        val gen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder(
            alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    fun putSecret(name: String, value: String) {
        if (value.isBlank()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
    fun getSecret(name: String): String {
        return try {
            val raw = prefs.getString(name, null) ?: return ""
            val parts = raw.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
        } catch (_: Exception) { "" }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CharacterTalkApp(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterTalkApp(context: Context) {
    val vault = remember { Vault(context) }
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(vault.getSecret("api_key")) }
    var baseUrl by remember { mutableStateOf(prefs.getString("base_url", "https://openrouter.ai/api/v1") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "qwen/qwen3-4b:free") ?: "") }
    var ttsUrl by remember { mutableStateOf(prefs.getString("tts_url", "https://api.openai.com/v1/audio/speech") ?: "") }
    var ttsKey by remember { mutableStateOf(vault.getSecret("tts_key")) }
    var ttsModel by remember { mutableStateOf(prefs.getString("tts_model", "gpt-4o-mini-tts") ?: "") }
    var ttsVoice by remember { mutableStateOf(prefs.getString("tts_voice", "alloy") ?: "") }
    var tab by remember { mutableStateOf(0) }
    var characters by remember { mutableStateOf(loadCharacters(prefs)) }
    var selectedId by remember { mutableLongStateOf(characters.first().id) }
    val selected = characters.firstOrNull { it.id == selectedId } ?: characters.first()
    var messages by remember(selectedId) { mutableStateOf(loadMessages(prefs, selectedId)) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var voiceMode by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var newPersonality by remember { mutableStateOf("") }
    var newScenario by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) status = "Microphone permission granted" else status = "Microphone permission denied"
    }

    fun saveAll() {
        vault.putSecret("api_key", apiKey)
        vault.putSecret("tts_key", ttsKey)
        prefs.edit().putString("base_url", baseUrl).putString("model", model)
            .putString("tts_url", ttsUrl).putString("tts_model", ttsModel)
            .putString("tts_voice", ttsVoice).apply()
    }

    suspend fun speak(text: String) {
        if (ttsUrl.isBlank() || ttsKey.isBlank()) {
            withContext(Dispatchers.Main) {
                if (tts == null) tts = TextToSpeech(context) { }
                tts?.language = Locale.US
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
            }
            return
        }
        try {
            val bytes = withContext(Dispatchers.IO) {
                val body = JSONObject().put("model", ttsModel).put("input", text)
                    .put("voice", ttsVoice).put("response_format", "mp3")
                val req = Request.Builder().url(ttsUrl).addHeader("Authorization", "Bearer $ttsKey")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                OkHttpClient().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("Voice API error ${r.code}: ${r.body?.string()}")
                    r.body!!.bytes()
                }
            }
            val file = File(context.cacheDir, "reply.mp3")
            file.writeBytes(bytes)
            withContext(Dispatchers.Main) {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath); setOnCompletionListener { it.release() }; prepare(); start()
                }
            }
        } catch (e: Exception) { status = "Voice error: ${e.message}" }
    }

    fun beginListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { status = "Speech recognition is unavailable on this device"; return }
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; status = "Listening…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; status = "Processing speech…" }
                override fun onError(error: Int) { listening = false; status = "Speech recognition stopped ($error)" }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) draft = spoken
                    status = if (spoken.isBlank()) "Didn't catch that" else "Speech captured"
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) { status = "Mic error: ${e.message}" }
    }

    suspend fun sendMessage(text: String) {
        if (text.isBlank() || busy) return
        if (apiKey.isBlank()) { status = "Add your chat API key in Settings first"; return }
        val userMessage = ChatMessage("user", text)
        messages = messages + userMessage
        draft = ""
        busy = true
        saveMessages(prefs, selectedId, messages)
        try {
            val reply = withContext(Dispatchers.IO) {
                val system = buildString {
                    append("You are ${selected.name}. ")
                    append(selected.description).append("\nPersonality: ").append(selected.personality)
                    append("\nScenario: ").append(selected.scenario)
                    append("\nStay in character and respond naturally.")
                }
                val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                (messages.takeLast(30) + userMessage).forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
                val body = JSONObject().put("model", model).put("messages", arr)
                    .put("temperature", 0.8).put("max_tokens", 700)
                val url = baseUrl.trimEnd('/') + "/chat/completions"
                val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://charactertalk.app")
                    .addHeader("X-Title", "CharacterTalk")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                OkHttpClient().newCall(req).execute().use { r ->
                    val response = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw Exception("Chat API error ${r.code}: $response")
                    JSONObject(response).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                }
            }
            messages = messages + ChatMessage("assistant", reply)
            saveMessages(prefs, selectedId, messages)
            status = "Reply received"
            if (voiceMode) speak(reply)
        } catch (e: Exception) { status = "Chat error: ${e.message}" }
        busy = false
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF9D8CFF), secondary = Color(0xFF65D6C2),
        background = Color(0xFF101018), surface = Color(0xFF191925)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Column {
                    Text("CharacterTalk", fontWeight = FontWeight.Bold)
                    Text(selected.name + " · " + status, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                } }, actions = {
                    IconButton(onClick = { voiceMode = !voiceMode; status = if (voiceMode) "Voice mode enabled" else "Text mode" }) {
                        Icon(if (voiceMode) Icons.Default.CallEnd else Icons.Default.Call, "Toggle voice mode")
                    }
                })
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Chat, null) }, label = { Text("Chat") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Characters") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            when (tab) {
                0 -> Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { voiceMode = !voiceMode }, label = { Text(if (voiceMode) "Voice call mode: ON" else "Voice call mode: OFF") },
                            leadingIcon = { Icon(Icons.Default.Call, null) })
                        if (voiceMode) Button(onClick = { if (!listening) beginListening() else { speechRecognizer?.stopListening(); listening = false } }) {
                            Icon(if (listening) Icons.Default.MicOff else Icons.Default.Mic, null)
                            Spacer(Modifier.width(6.dp)); Text(if (listening) "Stop mic" else "Talk")
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        items(messages) { msg ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.role == "user") Arrangement.End else Arrangement.Start) {
                                Surface(color = if (msg.role == "user") Color(0xFF34304D) else Color(0xFF242432),
                                    shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                                    Text(msg.content, Modifier.padding(12.dp), fontSize = 15.sp)
                                }
                            }
                        }
                        if (busy) item { Text("${selected.name} is thinking…", color = MaterialTheme.colorScheme.secondary) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { beginListening() }) { Icon(Icons.Default.Mic, "Speech to text") }
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Message ${selected.name}…") }, maxLines = 4)
                        IconButton(enabled = !busy && draft.isNotBlank(), onClick = { val value = draft; scope.launch { sendMessage(value) } }) {
                            Icon(Icons.Default.Send, "Send")
                        }
                    }
                }
                1 -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your characters", style = MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(characters) { character ->
                            Card(onClick = { selectedId = character.id; tab = 0 }, colors = CardDefaults.cardColors(
                                containerColor = if (character.id == selectedId) Color(0xFF34304D) else MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(character.name, fontWeight = FontWeight.Bold); Text(character.description, maxLines = 2, fontSize = 12.sp) }
                                    if (character.id == selectedId) Icon(Icons.Default.CheckCircle, null)
                                }
                            }
                        }
                    }
                    Text("Create character", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(newName, { newName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newDesc, { newDesc = it }, label = { Text("Description / lore") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newPersonality, { newPersonality = it }, label = { Text("Personality") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newScenario, { newScenario = it }, label = { Text("Scenario / roleplay setup") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (newName.isNotBlank()) {
                            val c = CharacterProfile(System.currentTimeMillis(), newName, newDesc, newPersonality, newScenario)
                            characters = characters + c; saveCharacters(prefs, characters); selectedId = c.id
                            newName = ""; newDesc = ""; newPersonality = ""; newScenario = ""; tab = 0
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save character") }
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("Chat model", style = MaterialTheme.typography.titleLarge) }
                    item { OutlinedTextField(apiKey, { apiKey = it }, label = { Text("OpenRouter / API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Chat API base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { Text("Example lightweight models: qwen/qwen3-4b:free · google/gemma-3-4b-it:free · meta-llama/llama-3.2-3b-instruct:free", fontSize = 12.sp) }
                    item { Divider() }
                    item { Text("AI voice (optional)", style = MaterialTheme.typography.titleLarge) }
                    item { OutlinedTextField(ttsUrl, { ttsUrl = it }, label = { Text("Speech API URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { OutlinedTextField(ttsKey, { ttsKey = it }, label = { Text("Speech API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { OutlinedTextField(ttsModel, { ttsModel = it }, label = { Text("Speech model") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { OutlinedTextField(ttsVoice, { ttsVoice = it }, label = { Text("Voice name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { Text("Leave speech API credentials blank to use Android's built-in text-to-speech voice.", fontSize = 12.sp) }
                    item { Button(onClick = { saveAll(); status = "Settings saved" }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") } }
                    item { Text("Privacy: API keys are encrypted with Android Keystore. Chat history and character profiles are stored locally on this device.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
                }
            }
        }
    }
}

fun loadCharacters(p: android.content.SharedPreferences): List<CharacterProfile> {
    return try {
        val a = JSONArray(p.getString("characters", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let {
            CharacterProfile(it.getLong("id"), it.getString("name"), it.optString("description"),
                it.optString("personality"), it.optString("scenario"))
        }}.ifEmpty { listOf(CharacterProfile(1, "Jasper", "A Vaporeon companion in a Pokémon-inspired world.", "Snarky, sarcastic, abrasive, but secretly affectionate.", "You are spending time together at home.")) }
    } catch (_: Exception) { listOf(CharacterProfile(1, "Jasper", "Your AI character.", "Snarky but affectionate.", "")) }
}
fun saveCharacters(p: android.content.SharedPreferences, list: List<CharacterProfile>) {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("description", it.description)
        .put("personality", it.personality).put("scenario", it.scenario)) }
    p.edit().putString("characters", a.toString()).apply()
}
fun loadMessages(p: android.content.SharedPreferences, id: Long): List<ChatMessage> {
    return try {
        val a = JSONArray(p.getString("messages_$id", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { ChatMessage(it.getString("role"), it.getString("content")) } }
    } catch (_: Exception) { emptyList() }
}
fun saveMessages(p: android.content.SharedPreferences, id: Long, messages: List<ChatMessage>) {
    val a = JSONArray()
    messages.takeLast(100).forEach { a.put(JSONObject().put("role", it.role).put("content", it.content)) }
    p.edit().putString("messages_$id", a.toString()).apply()
}
