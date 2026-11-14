package com.ultron.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private val BG = Color(0xFF070A10)
private val PANEL = Color(0xFF111925)
private val PANEL_LIGHT = Color(0xFF1B2735)
private val RED = Color(0xFFFF354F)
private val RED_SOFT = Color(0xFFFF7182)
private val WHITE = Color(0xFFEAF0F7)
private val MUTED = Color(0xFF8999AA)
private val GREEN = Color(0xFF49DDA0)
private val ORANGE = Color(0xFFFFB85C)

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
            body = "ULTRON online. Configure the Colab Cloud API in Settings to begin the conversation.",
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
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val body = item.optString("body").trim()
                    if (body.isNotEmpty()) {
                        add(
                            ChatMessage(
                                id = item.optLong("id", System.nanoTime()),
                                body = body,
                                fromUser = item.optBoolean("fromUser", false),
                                createdAt = item.optLong(
                                    "createdAt",
                                    System.currentTimeMillis()
                                )
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

        window.statusBarColor = android.graphics.Color.rgb(7, 10, 16)
        window.navigationBarColor = android.graphics.Color.rgb(7, 10, 16)

        setContent {
            UltronApp()
        }
    }
}

private class UltronSpeechController(
    context: Context,
    private val onSpeakingChanged: (Long?) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var activeMessageId: Long? = null
    @Volatile private var activeUtteranceId: String? = null

    init {
        engine = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finishUtterance(utteranceId)
                    override fun onError(utteranceId: String?) = finishUtterance(utteranceId)
                })
            }
        }
    }

    fun speak(text: String, messageId: Long) {
        val tts = engine
        if (tts == null || !ready) {
            Toast.makeText(
                appContext,
                "Text-to-Speech is not ready. Check the installed speech engine.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val isHindiScript = text.any { it in '\u0900'..'\u097F' }
        val preferredLocale = if (isHindiScript) Locale("hi", "IN") else Locale("en", "IN")
        var languageStatus = tts.setLanguage(preferredLocale)
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            languageStatus = tts.setLanguage(if (isHindiScript) Locale("hi") else Locale.ENGLISH)
        }
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            Toast.makeText(
                appContext,
                "Install a supported Hindi or English voice in Android Text-to-Speech settings.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val utteranceId = "ultron-$messageId"
        activeMessageId = messageId
        activeUtteranceId = utteranceId
        onSpeakingChanged(messageId)
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            activeMessageId = null
            activeUtteranceId = null
            onSpeakingChanged(null)
            Toast.makeText(appContext, "Unable to start speech playback.", Toast.LENGTH_SHORT).show()
        }
    }

    fun stop() {
        engine?.stop()
        activeMessageId = null
        activeUtteranceId = null
        onSpeakingChanged(null)
    }

    private fun finishUtterance(utteranceId: String?) {
        if (utteranceId != null && utteranceId == activeUtteranceId) {
            activeMessageId = null
            activeUtteranceId = null
            mainHandler.post { onSpeakingChanged(null) }
        }
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        activeMessageId = null
        activeUtteranceId = null
    }
}

@Composable
private fun UltronApp() {
    val context = LocalContext.current.applicationContext

    val apiPreferences = remember(context) {
        context.getSharedPreferences("ultron_api_config", Context.MODE_PRIVATE)
    }

    var apiBaseUrl by remember {
        mutableStateOf(apiPreferences.getString("base_url", "") ?: "")
    }

    var apiKey by remember {
        mutableStateOf(apiPreferences.getString("api_key", "") ?: "")
    }

    val messages = remember(context) {
        mutableStateListOf<ChatMessage>().apply {
            addAll(ChatHistory.load(context))
        }
    }

    var input by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var speakingMessageId by remember { mutableStateOf<Long?>(null) }
    val speechController = remember(context) {
        UltronSpeechController(context) { activeId ->
            mainHandler.post { speakingMessageId = activeId }
        }
    }
    DisposableEffect(speechController) {
        onDispose { speechController.shutdown() }
    }

    val apiConfigured = apiBaseUrl.isNotBlank() && apiKey.isNotBlank()

    fun saveHistory() {
        ChatHistory.save(context, messages)
    }

    fun copyConversation() {
        val transcript = messages.joinToString("\n\n") { message ->
            val speaker = if (message.fromUser) "YOU" else "ULTRON"
            val timestamp = DateFormat.format("dd MMM yyyy, HH:mm", Date(message.createdAt))
            "$speaker  •  $timestamp\n${message.body}"
        }
        if (transcript.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ULTRON conversation", transcript))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    fun sendMessage() {
        val cleanMessage = input.trim()
        if (cleanMessage.isEmpty() || isSending) return

        val userMessage = ChatMessage(
            id = System.nanoTime(),
            body = cleanMessage,
            fromUser = true,
            createdAt = System.currentTimeMillis()
        )

        messages.add(userMessage)
        input = ""
        saveHistory()

        val cleanBaseUrl = apiBaseUrl.trim().trimEnd('/')
        val cleanApiKey = apiKey.trim()

        if (cleanBaseUrl.isBlank() || cleanApiKey.isBlank()) {
            messages.add(
                ChatMessage(
                    id = System.nanoTime(),
                    body = "Cloud API is not configured. Open Settings and enter the HTTPS API URL and API Key from your running Colab notebook.",
                    fromUser = false,
                    createdAt = System.currentTimeMillis()
                )
            )
            saveHistory()
            return
        }

        val requestMessages = messages
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

        Thread {
            val responseText = try {
                UltronApiClient.sendChat(
                    baseUrl = cleanBaseUrl,
                    apiKey = cleanApiKey,
                    messages = requestMessages
                )
            } catch (error: Exception) {
                "Connection failed. Check that Colab and Cloudflare Tunnel are running, then verify the API URL and key in Settings.\n\nDetails: ${
                    error.message ?: error.javaClass.simpleName
                }"
            }

            mainHandler.post {
                val responseIndex = messages.indexOfFirst {
                    it.id == placeholderId
                }

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
        }.start()
    }

    fun clearHistory() {
        speechController.stop()
        messages.clear()
        messages.addAll(ChatHistory.welcome())
        saveHistory()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size + 1)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RED,
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
                        listOf(
                            Color(0xFF090D15),
                            BG,
                            Color(0xFF0C111A)
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            UltronTopBar(
                onSettings = { settingsOpen = true },
                onClear = { clearHistory() }
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF293646))
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 15.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    UltronHeroPanel(
                        apiConfigured = apiConfigured,
                        isSending = isSending
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "CONVERSATION",
                            color = WHITE,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )

                        Spacer(Modifier.weight(1f))

                        Text(
                            "${messages.size} MESSAGES",
                            color = MUTED,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }

                items(
                    items = messages,
                    key = { it.id }
                ) { message ->
                    UltronMessageBubble(
                        message = message,
                        isSpeaking = speakingMessageId == message.id,
                        onCopyConversation = { copyConversation() },
                        onSpeak = { speechController.speak(message.body, message.id) },
                        onStop = { speechController.stop() }
                    )
                }
            }

            UltronComposer(
                value = input,
                onValueChange = { input = it },
                onSend = { sendMessage() },
                isSending = isSending
            )

            Text(
                text = if (apiConfigured) {
                    "CLOUD API CONFIGURED  •  ULTRON"
                } else {
                    "API SETUP REQUIRED  •  OPEN SETTINGS"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 8.dp),
                textAlign = TextAlign.Center,
                color = if (apiConfigured) GREEN else ORANGE,
                fontSize = 8.sp,
                letterSpacing = 1.1.sp
            )
        }
    }

    if (settingsOpen) {
        UltronSettingsDialog(
            currentBaseUrl = apiBaseUrl,
            currentApiKey = apiKey,
            onSave = { newUrl, newKey ->
                apiBaseUrl = newUrl.trim().trimEnd('/')
                apiKey = newKey.trim()

                apiPreferences.edit()
                    .putString("base_url", apiBaseUrl)
                    .putString("api_key", apiKey)
                    .apply()

                settingsOpen = false
            },
            onDismiss = { settingsOpen = false }
        )
    }
}

@Composable
private fun UltronTopBar(
    onSettings: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 17.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF28131B))
                .border(1.dp, Color(0xFF873247), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_ultron_mark),
                contentDescription = "ULTRON emblem",
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
        }

        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(
                "ULTRON",
                color = WHITE,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )

            Text(
                "PERSONAL AI SYSTEM",
                color = MUTED,
                fontSize = 8.sp,
                letterSpacing = 1.5.sp
            )
        }

        Text(
            "⌫",
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onClear() }
                .padding(10.dp),
            color = MUTED,
            fontSize = 21.sp
        )

        Text(
            "⚙",
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onSettings() }
                .padding(10.dp),
            color = WHITE,
            fontSize = 21.sp
        )
    }
}

@Composable
private fun UltronHeroPanel(
    apiConfigured: Boolean,
    isSending: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF202C3A),
                        Color(0xFF111A26),
                        Color(0xFF0B1019)
                    )
                )
            )
            .border(
                1.dp,
                Color(0xFF3B4D5F),
                RoundedCornerShape(24.dp)
            )
            .padding(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "NEURAL INTERFACE",
                color = MUTED,
                fontSize = 9.sp,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.weight(1f))

            Text(
                "MK-I",
                color = RED_SOFT,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            NeuralCore()

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    "I AM",
                    color = MUTED,
                    fontSize = 9.sp,
                    letterSpacing = 2.sp
                )

                Text(
                    "ULTRON",
                    color = WHITE,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )

                Spacer(Modifier.height(5.dp))

                Text(
                    "Personal Intelligence Interface",
                    color = MUTED,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )

                Spacer(Modifier.height(12.dp))

                StatusPill(
                    label = when {
                        isSending -> "PROCESSING REQUEST"
                        apiConfigured -> "API CONFIGURED"
                        else -> "AWAITING API SETUP"
                    },
                    active = apiConfigured || isSending
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF344354))
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniMetric("ENGINE", "QWEN")
            Spacer(Modifier.width(18.dp))
            MiniMetric("CONNECTION", if (apiConfigured) "CONFIGURED" else "PENDING")
            Spacer(Modifier.weight(1f))
            Text(
                "SYSTEM READY",
                color = if (apiConfigured) GREEN else ORANGE,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun MiniMetric(
    title: String,
    value: String
) {
    Column {
        Text(
            title,
            color = MUTED,
            fontSize = 7.sp,
            letterSpacing = 1.sp
        )
        Text(
            value,
            color = WHITE,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    active: Boolean
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (active) Color(0xFF142A27) else Color(0xFF30251B)
            )
            .border(
                1.dp,
                if (active) Color(0xFF286B54) else Color(0xFF73502D),
                CircleShape
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) GREEN else ORANGE)
        )

        Spacer(Modifier.width(7.dp))

        Text(
            label,
            color = if (active) GREEN else ORANGE,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .7.sp
        )
    }
}

@Composable
private fun NeuralCore() {
    val transition = rememberInfiniteTransition(label = "ultron-core")

    val pulse by transition.animateFloat(
        initialValue = 0.91f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1500,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core-pulse"
    )

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 9000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "core-rotation"
    )

    Canvas(
        modifier = Modifier.size(132.dp)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val radius = size.minDimension * .37f * pulse
        val ringBounds = Size(radius * 2f, radius * 2f)
        val ringTopLeft = Offset(cx - radius, cy - radius)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x88FF304F),
                    Color(0x33FF304F),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 1.65f
            ),
            radius = radius * 1.65f,
            center = center
        )

        drawCircle(
            color = Color(0x557D91A5),
            radius = radius * 1.25f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        drawCircle(
            color = Color(0x667D91A5),
            radius = radius * .92f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        drawArc(
            color = RED,
            startAngle = rotation,
            sweepAngle = 105f,
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringBounds,
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        )

        drawArc(
            color = RED_SOFT.copy(alpha = .75f),
            startAngle = rotation + 180f,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = Offset(
                cx - radius * .78f,
                cy - radius * .78f
            ),
            size = Size(radius * 1.56f, radius * 1.56f),
            style = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        )

        drawCircle(
            color = Color(0xFF172330),
            radius = radius * .66f,
            center = center
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFF8998),
                    RED,
                    Color(0xFF641327)
                ),
                center = center,
                radius = radius * .72f
            ),
            radius = radius * .47f,
            center = center
        )

        drawCircle(
            color = Color(0xFFFFD5DA),
            radius = radius * .12f,
            center = center
        )

        drawLine(
            color = Color(0xFFFFB5BF),
            start = Offset(cx - radius * .22f, cy + radius * .72f),
            end = Offset(cx + radius * .22f, cy + radius * .72f),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun UltronMessageBubble(
    message: ChatMessage,
    isSpeaking: Boolean,
    onCopyConversation: () -> Unit,
    onSpeak: () -> Unit,
    onStop: () -> Unit
) {
    val user = message.fromUser

    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (user) 18.dp else 5.dp,
        bottomEnd = if (user) 5.dp else 18.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape)
                .background(
                    if (user) Color(0xFF29151E) else PANEL
                )
                .border(
                    1.dp,
                    if (user) Color(0xFF783246) else Color(0xFF2B3B4D),
                    bubbleShape
                )
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (user) "YOU" else "ULTRON",
                    color = if (user) RED_SOFT else RED,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )

                Spacer(Modifier.weight(1f))

                Text(
                    DateFormat.format("HH:mm", Date(message.createdAt)).toString(),
                    color = MUTED,
                    fontSize = 9.sp
                )
            }

            Spacer(Modifier.height(7.dp))

            Text(
                text = message.body,
                color = WHITE,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
            if (!user && !message.body.startsWith("Connecting to ULTRON Cloud")) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MessageActionButton(
                        label = "COPY CHAT",
                        active = false,
                        onClick = onCopyConversation
                    )
                    MessageActionButton(
                        label = if (isSpeaking) "STOP AUDIO" else "READ ALOUD",
                        active = isSpeaking,
                        onClick = if (isSpeaking) onStop else onSpeak
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageActionButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val accent = if (active) RED_SOFT else Color(0xFF9BAEC0)
    Text(
        text = label,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) Color(0xFF351820) else Color(0xFF192431))
            .border(1.dp, if (active) Color(0xFF873247) else Color(0xFF34465A), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        color = accent,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .6.sp
    )
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
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(PANEL)
            .border(1.dp, Color(0xFF34465A), RoundedCornerShape(25.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(37.dp)
                .clip(CircleShape)
                .background(Color(0xFF202B39)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "U",
                color = RED_SOFT,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(Modifier.width(10.dp))

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = !isSending,
            textStyle = TextStyle(
                color = WHITE,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(RED),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = { onSend() }
            ),
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
                .size(43.dp)
                .clip(CircleShape)
                .background(
                    if (value.isNotBlank() && !isSending) RED
                    else Color(0xFF343E4B)
                )
                .clickable(
                    enabled = value.isNotBlank() && !isSending
                ) {
                    onSend()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (isSending) "…" else "➤",
                color = WHITE,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun UltronSettingsDialog(
    currentBaseUrl: String,
    currentApiKey: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember(currentBaseUrl) {
        mutableStateOf(currentBaseUrl)
    }

    var key by remember(currentApiKey) {
        mutableStateOf(currentApiKey)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151F2B),
        title = {
            Column {
                Text(
                    "SYSTEM CONFIGURATION",
                    color = WHITE,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp
                )

                Text(
                    "Connect ULTRON to your Colab Qwen backend",
                    color = MUTED,
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "HTTPS API URL",
                    color = RED_SOFT,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            "https://your-tunnel.trycloudflare.com",
                            fontSize = 11.sp
                        )
                    },
                    textStyle = TextStyle(
                        color = WHITE,
                        fontSize = 12.sp
                    )
                )

                Text(
                    "API KEY",
                    color = RED_SOFT,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = {
                        Text(
                            "Paste your Colab API key",
                            fontSize = 11.sp
                        )
                    },
                    textStyle = TextStyle(
                        color = WHITE,
                        fontSize = 12.sp
                    )
                )

                Text(
                    "The API key is stored in this app's private preferences. It is not encrypted with Android Keystore.",
                    color = MUTED,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url, key) },
                enabled = url.trim().startsWith("https://") && key.isNotBlank()
            ) {
                Text(
                    "SAVE CONFIGURATION",
                    color = RED_SOFT,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = MUTED)
            }
        }
    )
}
