package com.ultron.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

private val BG = Color(0xFF07080D)
private val PANEL = Color(0xFF11131B)
private val PANEL_RAISED = Color(0xFF181B25)
private val CRIMSON = Color(0xFFB51231)
private val CRIMSON_BRIGHT = Color(0xFFFF3658)
private val WHITE = Color(0xFFF2F3F7)
private val MUTED = Color(0xFF89909F)
private val GREEN = Color(0xFF55D6A1)
private val AMBER = Color(0xFFF0B45C)

private data class ChatMessage(
    val id: Long,
    val body: String,
    val fromUser: Boolean,
    val createdAt: Long
)

private object ChatHistory {
    private const val PREFS = "ultron_local_history"
    private const val KEY = "messages"

    fun welcome(): List<ChatMessage> = listOf(
        ChatMessage(
            id = System.nanoTime(),
            body = "ULTRON systems initialized. Configure the secure cloud connection in Settings to begin.",
            fromUser = false,
            createdAt = System.currentTimeMillis()
        )
    )

    fun load(context: Context): List<ChatMessage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        if (raw.isNullOrBlank()) return welcome()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val body = item.optString("body").trim()
                    if (body.isNotEmpty()) {
                        add(
                            ChatMessage(
                                id = item.optLong("id", System.nanoTime()),
                                body = body,
                                fromUser = item.optBoolean("fromUser", false),
                                createdAt = item.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                }
            }.ifEmpty { welcome() }
        }.getOrElse { welcome() }
    }

    fun save(context: Context, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(200).forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("body", message.body)
                    .put("fromUser", message.fromUser)
                    .put("createdAt", message.createdAt)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 8, 13)
        window.navigationBarColor = android.graphics.Color.rgb(7, 8, 13)
        window.decorView.systemUiVisibility = 0
        setContent { UltronApp() }
    }
}

@Composable
private fun UltronApp() {
    val context = LocalContext.current.applicationContext
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val voicePreferences = remember(context) {
        context.getSharedPreferences("ultron_voice_settings", Context.MODE_PRIVATE)
    }
    val savedConfig = remember(context) { SecureConfigStore.load(context) }

    var apiBaseUrl by remember { mutableStateOf(savedConfig.baseUrl) }
    var apiKey by remember { mutableStateOf(savedConfig.apiKey) }
    var voiceLanguage by remember {
        mutableStateOf(voicePreferences.getString("language", "auto") ?: "auto")
    }
    var voiceSpeed by remember {
        mutableStateOf(voicePreferences.getFloat("speed", 0.92f).coerceIn(0.75f, 1.25f))
    }

    val messages = remember(context) {
        mutableStateListOf<ChatMessage>().apply { addAll(ChatHistory.load(context)) }
    }
    var input by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var speakingMessageId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    val audioController = remember(context) {
        UltronAudioController(
            context = context,
            onPlaybackChanged = { activeId ->
                mainHandler.post { speakingMessageId = activeId }
            },
            onError = { message ->
                mainHandler.post {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }
    DisposableEffect(audioController) {
        onDispose { audioController.shutdown() }
    }

    val apiConfigured = apiBaseUrl.isNotBlank() && apiKey.isNotBlank()

    fun saveHistory() = ChatHistory.save(context, messages)

    fun playMessage(message: ChatMessage, forcedLanguage: String? = null) {
        if (!apiConfigured) {
            Toast.makeText(context, "Configure the ULTRON API in Settings first.", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedLanguage = forcedLanguage ?: when (voiceLanguage) {
            "en" -> "en"
            "hi" -> "hi"
            else -> if (message.body.any { it in '\u0900'..'\u097F' }) "hi" else "en"
        }
        audioController.play(
            baseUrl = apiBaseUrl,
            apiKey = apiKey,
            text = message.body,
            language = selectedLanguage,
            speed = voiceSpeed,
            messageId = message.id
        )
    }

    fun copyText(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    fun copyConversation() {
        val transcript = messages.joinToString("\n\n") { message ->
            val speaker = if (message.fromUser) "YOU" else "ULTRON"
            val timestamp = DateFormat.format("dd MMM yyyy, HH:mm", Date(message.createdAt))
            "$speaker • $timestamp\n${message.body}"
        }
        if (transcript.isNotBlank()) copyText(transcript, "ULTRON conversation")
    }

    fun sendMessage() {
        val cleanMessage = input.trim()
        if (cleanMessage.isEmpty() || isSending) return

        val cleanBaseUrl = apiBaseUrl.trim().trimEnd('/')
        val cleanApiKey = apiKey.trim()
        val userMessage = ChatMessage(
            id = System.nanoTime(),
            body = cleanMessage,
            fromUser = true,
            createdAt = System.currentTimeMillis()
        )
        messages.add(userMessage)
        input = ""
        saveHistory()

        if (cleanBaseUrl.isBlank() || cleanApiKey.isBlank()) {
            messages.add(
                ChatMessage(
                    id = System.nanoTime(),
                    body = "Cloud API is not configured. Open Settings and enter your HTTPS tunnel URL and API key.",
                    fromUser = false,
                    createdAt = System.currentTimeMillis()
                )
            )
            saveHistory()
            return
        }

        val requestMessages = messages
            .filterNot { it.body.startsWith("Connecting to ULTRON") }
            .takeLast(12)
            .map { message ->
                UltronApiMessage(
                    role = if (message.fromUser) "user" else "assistant",
                    content = message.body
                )
            }

        val placeholderId = System.nanoTime()
        messages.add(
            ChatMessage(
                id = placeholderId,
                body = "Connecting to ULTRON Cloud...",
                fromUser = false,
                createdAt = System.currentTimeMillis()
            )
        )
        isSending = true

        Thread({
            val responseText = try {
                UltronApiClient.sendChat(cleanBaseUrl, cleanApiKey, requestMessages)
            } catch (error: Exception) {
                "Connection failed. Confirm that Colab and Cloudflare Tunnel are running, then check Settings.\n\n${error.message ?: error.javaClass.simpleName}"
            }

            mainHandler.post {
                val responseIndex = messages.indexOfFirst { it.id == placeholderId }
                if (responseIndex >= 0) {
                    messages[responseIndex] = ChatMessage(
                        id = placeholderId,
                        body = responseText,
                        fromUser = false,
                        createdAt = System.currentTimeMillis()
                    )
                }
                isSending = false
                saveHistory()
            }
        }, "ultron-chat-request").start()
    }

    fun clearHistory() {
        audioController.stop()
        messages.clear()
        messages.addAll(ChatHistory.welcome())
        saveHistory()
    }

    LaunchedEffect(messages.size, isSending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size + if (isSending) 1 else 0)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CRIMSON_BRIGHT,
            secondary = CRIMSON,
            background = BG,
            surface = PANEL,
            onBackground = WHITE,
            onSurface = WHITE
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A0B11), BG, Color(0xFF090A0F))
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            UltronTopBar(
                apiConfigured = apiConfigured,
                isSending = isSending,
                isSpeaking = speakingMessageId != null,
                onSettings = { settingsOpen = true },
                onClear = { clearHistory() },
                onStop = { audioController.stop() },
                onCopy = { copyConversation() }
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF33202A))
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                item {
                    SystemStatusLine(
                        apiConfigured = apiConfigured,
                        isSending = isSending
                    )
                }

                items(items = messages, key = { it.id }) { message ->
                    UltronMessageBubble(
                        message = message,
                        isSpeaking = speakingMessageId == message.id,
                        onCopy = { copyText(message.body, "ULTRON message") },
                        onSpeak = { playMessage(message) },
                        onStop = { audioController.stop() }
                    )
                }

                if (isSending) {
                    item { TypingIndicator() }
                }
            }

            UltronComposer(
                value = input,
                onValueChange = { input = it },
                onSend = { sendMessage() },
                isSending = isSending
            )

            Text(
                text = when {
                    !apiConfigured -> "SETUP REQUIRED  •  OPEN SETTINGS"
                    isSending -> "QWEN IS PROCESSING"
                    speakingMessageId != null -> "KOKORO VOICE ACTIVE"
                    else -> "QWEN 3.5  •  KOKORO 82M  •  SECURE API"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp, bottom = 7.dp),
                textAlign = TextAlign.Center,
                color = when {
                    !apiConfigured -> AMBER
                    isSending || speakingMessageId != null -> CRIMSON_BRIGHT
                    else -> GREEN
                },
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.15.sp
            )
        }
    }

    if (settingsOpen) {
        UltronSettingsDialog(
            currentBaseUrl = apiBaseUrl,
            currentApiKey = apiKey,
            currentVoiceLanguage = voiceLanguage,
            currentVoiceSpeed = voiceSpeed,
            onSave = { newUrl, newKey, newLanguage, newSpeed ->
                val cleanUrl = newUrl.trim().trimEnd('/')
                val cleanKey = newKey.trim()
                runCatching { SecureConfigStore.save(context, cleanUrl, cleanKey) }
                    .onSuccess {
                        apiBaseUrl = cleanUrl
                        apiKey = cleanKey
                        voiceLanguage = newLanguage
                        voiceSpeed = newSpeed
                        voicePreferences.edit()
                            .putString("language", newLanguage)
                            .putFloat("speed", newSpeed)
                            .apply()
                        settingsOpen = false
                        Toast.makeText(context, "ULTRON settings saved securely.", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            context,
                            "Unable to save settings: ${error.message ?: "Unknown error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            },
            onTestVoice = { language, speed ->
                val sample = if (language == "hi") {
                    "अल्ट्रॉन सिस्टम ऑनलाइन है, सर।"
                } else {
                    "ULTRON systems are online, Sir."
                }
                if (apiBaseUrl.isBlank() || apiKey.isBlank()) {
                    Toast.makeText(context, "Save your API URL and key before testing voice.", Toast.LENGTH_SHORT).show()
                } else {
                    audioController.play(
                        baseUrl = apiBaseUrl,
                        apiKey = apiKey,
                        text = sample,
                        language = language,
                        speed = speed,
                        messageId = Long.MIN_VALUE
                    )
                }
            },
            onStopVoice = { audioController.stop() },
            onDismiss = { settingsOpen = false }
        )
    }
}

@Composable
private fun UltronTopBar(
    apiConfigured: Boolean,
    isSending: Boolean,
    isSpeaking: Boolean,
    onSettings: () -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF2A0C17))
                .border(1.dp, Color(0xFF7D1830), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_ultron_mark),
                contentDescription = "ULTRON emblem",
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                "ULTRON",
                color = WHITE,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.7.sp
            )
            Text(
                "QWEN 3.5  /  KOKORO VOICE",
                color = MUTED,
                fontSize = 8.sp,
                letterSpacing = .9.sp
            )
        }

        if (isSpeaking) {
            HeaderAction("■", "Stop voice", onStop, active = true)
        }
        HeaderAction("▤", "Copy conversation", onCopy)
        HeaderAction("⌫", "Clear history", onClear)
        HeaderAction("⚙", "Settings", onSettings, active = true)
    }
}

@Composable
private fun HeaderAction(
    label: String,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        color = if (active) CRIMSON_BRIGHT else MUTED,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SystemStatusLine(
    apiConfigured: Boolean,
    isSending: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF12131A))
            .border(1.dp, Color(0xFF29202A), RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (apiConfigured) GREEN else AMBER)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                isSending -> "PROCESSING REQUEST"
                apiConfigured -> "CLOUD LINK CONFIGURED"
                else -> "AWAITING SECURE CONFIGURATION"
            },
            color = if (apiConfigured) GREEN else AMBER,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.05.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            "LOCAL HISTORY  •  PRIVATE",
            color = MUTED,
            fontSize = 7.sp,
            letterSpacing = .65.sp
        )
    }
}

@Composable
private fun UltronMessageBubble(
    message: ChatMessage,
    isSpeaking: Boolean,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onStop: () -> Unit
) {
    val user = message.fromUser
    val shape = RoundedCornerShape(
        topStart = 17.dp,
        topEnd = 17.dp,
        bottomStart = if (user) 17.dp else 5.dp,
        bottomEnd = if (user) 5.dp else 17.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(if (user) Color(0xFF2B101B) else PANEL)
                .border(
                    1.dp,
                    if (user) Color(0xFF70243A) else Color(0xFF292C37),
                    shape
                )
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (user) "YOU" else "ULTRON",
                    color = if (user) Color(0xFFFF899E) else CRIMSON_BRIGHT,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    DateFormat.format("HH:mm", Date(message.createdAt)).toString(),
                    color = MUTED,
                    fontSize = 8.sp
                )
            }

            Spacer(Modifier.height(7.dp))
            Text(
                text = message.body,
                color = WHITE,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )

            if (!user && !message.body.startsWith("Connecting to ULTRON")) {
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    BubbleAction("COPY", onCopy)
                    if (isSpeaking) {
                        BubbleAction("STOP AUDIO", onStop, active = true)
                    } else {
                        BubbleAction("PLAY VOICE", onSpeak)
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleAction(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) Color(0xFF3A101E) else PANEL_RAISED)
            .border(
                1.dp,
                if (active) Color(0xFF8E2440) else Color(0xFF343744),
                CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (active) Color(0xFFFF8CA2) else Color(0xFFB7BDCA),
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .65.sp
    )
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PANEL)
            .border(1.dp, Color(0xFF292C37), RoundedCornerShape(16.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            color = CRIMSON_BRIGHT,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(9.dp))
        Text("ULTRON is processing...", color = MUTED, fontSize = 11.sp)
    }
}

@Composable
private fun UltronComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(PANEL)
            .border(1.dp, Color(0xFF3A2630), RoundedCornerShape(25.dp))
            .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("U", color = CRIMSON_BRIGHT, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(11.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = !isSending,
            textStyle = TextStyle(color = WHITE, fontSize = 14.sp),
            cursorBrush = SolidColor(CRIMSON_BRIGHT),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            if (isSending) "ULTRON is thinking..." else "Message ULTRON...",
                            color = MUTED,
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            }
        )
        Spacer(Modifier.width(9.dp))
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (value.isNotBlank() && !isSending) CRIMSON else Color(0xFF343640))
                .clickable(enabled = value.isNotBlank() && !isSending) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Text("➤", color = WHITE, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UltronSettingsDialog(
    currentBaseUrl: String,
    currentApiKey: String,
    currentVoiceLanguage: String,
    currentVoiceSpeed: Float,
    onSave: (String, String, String, Float) -> Unit,
    onTestVoice: (String, Float) -> Unit,
    onStopVoice: () -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember(currentBaseUrl) { mutableStateOf(currentBaseUrl) }
    var key by remember(currentApiKey) { mutableStateOf(currentApiKey) }
    var language by remember(currentVoiceLanguage) { mutableStateOf(currentVoiceLanguage) }
    var speed by remember(currentVoiceSpeed) { mutableStateOf(currentVoiceSpeed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15131B),
        title = {
            Column {
                Text(
                    "SYSTEM SETTINGS",
                    color = WHITE,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp
                )
                Text("Cloud connection and Kokoro voice", color = MUTED, fontSize = 10.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SettingsLabel("HTTPS API URL")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://your-tunnel.trycloudflare.com", fontSize = 10.sp) },
                    textStyle = TextStyle(color = WHITE, fontSize = 11.sp)
                )

                SettingsLabel("API KEY")
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text("Paste your private API key", fontSize = 10.sp) },
                    textStyle = TextStyle(color = WHITE, fontSize = 11.sp)
                )
                Text(
                    "The API key is encrypted with Android Keystore. Quick Tunnel URLs can change after a Colab restart.",
                    color = MUTED,
                    fontSize = 9.sp,
                    lineHeight = 13.sp
                )

                Box(
                    Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF34222B))
                )
                SettingsLabel("KOKORO VOICE")
                Text(
                    "Male voices: English am_onyx  •  Hindi hm_omega",
                    color = MUTED,
                    fontSize = 9.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VoiceChoice("AUTO", language == "auto") { language = "auto" }
                    VoiceChoice("ENGLISH", language == "en") { language = "en" }
                    VoiceChoice("HINDI", language == "hi") { language = "hi" }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsLabel("SPEECH SPEED")
                    Spacer(Modifier.weight(1f))
                    Text("${String.format(java.util.Locale.US, "%.2f", speed)}x", color = CRIMSON_BRIGHT, fontSize = 10.sp)
                }
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 0.75f..1.25f
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val testLanguage = if (language == "hi") "hi" else "en"
                            onTestVoice(testLanguage, speed)
                        }
                    ) { Text("TEST VOICE", color = CRIMSON_BRIGHT, fontSize = 10.sp) }
                    TextButton(onClick = onStopVoice) {
                        Text("STOP", color = MUTED, fontSize = 10.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url, key, language, speed) },
                enabled = url.trim().startsWith("https://", ignoreCase = true) && key.isNotBlank()
            ) {
                Text("SAVE", color = CRIMSON_BRIGHT, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = MUTED) }
        }
    )
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text,
        color = Color(0xFFFF8499),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .9.sp
    )
}

@Composable
private fun VoiceChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        label,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Color(0xFF3A101E) else PANEL_RAISED)
            .border(
                1.dp,
                if (selected) Color(0xFF9B2946) else Color(0xFF343744),
                CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        color = if (selected) Color(0xFFFF8CA2) else MUTED,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold
    )
}
