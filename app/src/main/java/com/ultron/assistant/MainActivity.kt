package com.ultron.assistant

import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

private val BG = Color(0xFF080A10)
private val PANEL = Color(0xFF111722)
private val PANEL_LIGHT = Color(0xFF192330)
private val RED = Color(0xFFFF354F)
private val RED_DARK = Color(0xFF6D1D2B)
private val WHITE = Color(0xFFEAF0F7)
private val MUTED = Color(0xFF8C9AAA)
private val GREEN = Color(0xFF48D99A)

private data class ChatMessage(
    val body: String,
    val fromUser: Boolean,
    val createdAt: Long
)

private object ChatHistory {

    private const val PREFS = "ultron_local_history"
    private const val KEY = "messages"

    fun welcome(): List<ChatMessage> = listOf(
        ChatMessage(
            body = "Welcome back. I am ULTRON. My interface is ready. Cloud AI connection is pending.",
            fromUser = false,
            createdAt = System.currentTimeMillis()
        )
    )

    fun load(context: Context): List<ChatMessage> {
        val raw = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
                    .put("body", message.body)
                    .put("fromUser", message.fromUser)
                    .put("createdAt", message.createdAt)
            )
        }

        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.rgb(8, 10, 16)
        window.navigationBarColor = android.graphics.Color.rgb(8, 10, 16)

        setContent {
            UltronApplication()
        }
    }
}

@Composable
private fun UltronApplication() {

    val context = LocalContext.current.applicationContext

    val messages = remember(context) {
        mutableStateListOf<ChatMessage>().apply {
            addAll(ChatHistory.load(context))
        }
    }

    var input by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val listState = rememberLazyListState()

    fun sendMessage() {
        val cleanMessage = input.trim()
        if (cleanMessage.isEmpty()) return

        messages.add(
            ChatMessage(
                body = cleanMessage,
                fromUser = true,
                createdAt = System.currentTimeMillis()
            )
        )

        messages.add(
            ChatMessage(
                body = "Message saved locally. The ULTRON Cloud API is not connected in V1, so this message has not been sent to Qwen or Google Colab.",
                fromUser = false,
                createdAt = System.currentTimeMillis()
            )
        )

        ChatHistory.save(context, messages)
        input = ""
    }

    fun clearHistory() {
        messages.clear()
        messages.addAll(ChatHistory.welcome())
        ChatHistory.save(context, messages)
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
                        listOf(BG, Color(0xFF0D111A), BG)
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
                    .background(Color(0xFF26313F))
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 17.dp,
                    end = 17.dp,
                    top = 18.dp,
                    bottom = 18.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                item {
                    UltronIdentityPanel()
                }

                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "CONVERSATION",
                            color = WHITE,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )

                        Spacer(Modifier.weight(1f))

                        Text(
                            "LOCAL HISTORY",
                            color = MUTED,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }

                itemsIndexed(
                    items = messages,
                    key = { index, message -> "${message.createdAt}-$index" }
                ) { _, message ->
                    UltronMessageBubble(message)
                }
            }

            UltronComposer(
                value = input,
                onValueChange = { input = it },
                onSend = { sendMessage() }
            )

            Text(
                text = "V1.0.0  •  CLOUD API NOT CONNECTED  •  ULTRON",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp, top = 2.dp),
                textAlign = TextAlign.Center,
                color = MUTED,
                fontSize = 8.sp,
                letterSpacing = 1.1.sp
            )
        }
    }

    if (settingsOpen) {
        UltronSettingsDialog(
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
            .padding(horizontal = 19.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            Modifier
                .size(43.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF24131A))
                .border(
                    1.dp,
                    Color(0xFF7A2838),
                    RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "U",
                color = RED,
                fontSize = 25.sp,
                fontWeight = FontWeight.Black
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFB85C))
                )

                Spacer(Modifier.width(6.dp))

                Text(
                    "PERSONAL AI INTERFACE",
                    color = MUTED,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        Text(
            "⌫",
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onClear() }
                .padding(11.dp),
            color = MUTED,
            fontSize = 20.sp
        )

        Text(
            "⚙",
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onSettings() }
                .padding(11.dp),
            color = WHITE,
            fontSize = 20.sp
        )
    }
}

@Composable
private fun UltronIdentityPanel() {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(25.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF202A36),
                        Color(0xFF111923),
                        Color(0xFF0B1019)
                    )
                )
            )
            .border(
                1.dp,
                Color(0xFF394958),
                RoundedCornerShape(25.dp)
            )
            .padding(horizontal = 15.dp, vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "NEURAL CORE",
                color = MUTED,
                fontSize = 9.sp,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.weight(1f))

            Text(
                "UNIT 01",
                color = RED,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        UltronAvatar(
            Modifier
                .fillMaxWidth()
                .height(218.dp)
        )

        Text(
            "ULTRON",
            color = WHITE,
            fontSize = 25.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 5.sp
        )

        Spacer(Modifier.height(5.dp))

        Text(
            "THE PERSONAL INTELLIGENCE INTERFACE",
            color = MUTED,
            fontSize = 9.sp,
            letterSpacing = 1.3.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(13.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0xFF241D1B))
                .border(
                    1.dp,
                    Color(0xFF6A4430),
                    CircleShape
                )
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFB85C))
            )

            Spacer(Modifier.width(7.dp))

            Text(
                "WAITING FOR CLOUD CONNECTION",
                color = Color(0xFFFFC77B),
                fontSize = 8.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun UltronAvatar(modifier: Modifier = Modifier) {

    Canvas(modifier) {

        val scale = minOf(
            size.width / 320f,
            size.height / 260f
        )

        val centerX = size.width / 2f
        val offsetY = (size.height - 260f * scale) / 2f

        fun point(x: Float, y: Float): Offset {
            return Offset(
                centerX + x * scale,
                offsetY + y * scale
            )
        }

        fun polygon(vararg values: Float): Path {
            require(values.size >= 6 && values.size % 2 == 0)

            return Path().apply {
                moveTo(point(values[0], values[1]).x, point(values[0], values[1]).y)

                var index = 2
                while (index < values.size) {
                    val p = point(values[index], values[index + 1])
                    lineTo(p.x, p.y)
                    index += 2
                }

                close()
            }
        }

        val metal = Brush.linearGradient(
            colors = listOf(
                Color(0xFFD1D9DE),
                Color(0xFF788695),
                Color(0xFF303C49),
                Color(0xFF151D27)
            ),
            start = point(-110f, 5f),
            end = point(110f, 245f)
        )

        val darkMetal = Brush.linearGradient(
            colors = listOf(
                Color(0xFF526171),
                Color(0xFF232D39),
                Color(0xFF0C121B)
            ),
            start = point(-70f, 30f),
            end = point(70f, 245f)
        )

        val redMetal = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF6572),
                Color(0xFFFF263E),
                Color(0xFF741728)
            ),
            startY = offsetY + 35f * scale,
            endY = offsetY + 230f * scale
        )

        // Reactor halo behind the figure.
        drawCircle(
            color = Color(0x22FF304A),
            radius = 108f * scale,
            center = point(0f, 132f)
        )

        drawCircle(
            color = Color(0x44FF304A),
            radius = 95f * scale,
            center = point(0f, 132f),
            style = Stroke(1.2f * scale)
        )

        drawCircle(
            color = Color(0x44AAB8C6),
            radius = 81f * scale,
            center = point(0f, 132f),
            style = Stroke(0.7f * scale)
        )

        // Arms and outer armour.
        val leftArm = polygon(
            -58f, 96f,
            -83f, 98f,
            -108f, 139f,
            -99f, 165f,
            -79f, 169f,
            -67f, 144f,
            -43f, 121f
        )

        val rightArm = polygon(
            58f, 96f,
            83f, 98f,
            108f, 139f,
            99f, 165f,
            79f, 169f,
            67f, 144f,
            43f, 121f
        )

        drawPath(leftArm, darkMetal)
        drawPath(rightArm, darkMetal)

        drawPath(leftArm, Color(0xFF73818F), style = Stroke(1.5f * scale))
        drawPath(rightArm, Color(0xFF73818F), style = Stroke(1.5f * scale))

        // Main torso.
        val torso = polygon(
            -42f, 91f,
            -69f, 107f,
            -61f, 207f,
            -43f, 232f,
            0f, 250f,
            43f, 232f,
            61f, 207f,
            69f, 107f,
            42f, 91f,
            0f, 109f
        )

        drawPath(torso, darkMetal)
        drawPath(torso, Color(0xFF8090A0), style = Stroke(1.4f * scale))

        // Shoulder armour plates.
        val leftShoulder = polygon(
            -23f, 88f,
            -58f, 83f,
            -91f, 104f,
            -83f, 131f,
            -51f, 137f,
            -34f, 118f
        )

        val rightShoulder = polygon(
            23f, 88f,
            58f, 83f,
            91f, 104f,
            83f, 131f,
            51f, 137f,
            34f, 118f
        )

        drawPath(leftShoulder, metal)
        drawPath(rightShoulder, metal)

        drawPath(
            leftShoulder,
            Color(0xFFCAD3DA),
            style = Stroke(1.5f * scale)
        )

        drawPath(
            rightShoulder,
            Color(0xFFCAD3DA),
            style = Stroke(1.5f * scale)
        )

        // Neck and neck armour.
        drawRoundRect(
            brush = darkMetal,
            topLeft = point(-18f, 70f),
            size = Size(36f * scale, 34f * scale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * scale)
        )

        // Helmet outer silhouette.
        val helmet = polygon(
            -24f, 22f,
            -19f, 9f,
            0f, 2f,
            19f, 9f,
            24f, 22f,
            29f, 53f,
            20f, 77f,
            10f, 91f,
            -10f, 91f,
            -20f, 77f,
            -29f, 53f
        )

        drawPath(helmet, metal)

        drawPath(
            helmet,
            Color(0xFFE1E7EB),
            style = Stroke(1.7f * scale)
        )

        // Forehead armour.
        drawPath(
            polygon(
                -13f, 12f,
                0f, 7f,
                13f, 12f,
                8f, 30f,
                -8f, 30f
            ),
            darkMetal
        )

        // Face plate.
        drawPath(
            polygon(
                -22f, 31f,
                22f, 31f,
                18f, 68f,
                9f, 83f,
                -9f, 83f,
                -18f, 68f
            ),
            darkMetal
        )

        // Side helmet guards.
        for (side in listOf(-1f, 1f)) {
            val ear = point(side * 25f, 44f)

            drawCircle(
                color = Color(0xFF17212B),
                radius = 12f * scale,
                center = ear
            )

            drawCircle(
                color = RED.copy(alpha = .85f),
                radius = 8f * scale,
                center = ear,
                style = Stroke(2.2f * scale)
            )
        }

        // Eyes.
        for (side in listOf(-1f, 1f)) {

            drawPath(
                polygon(
                    side * 4f, 40f,
                    side * 19f, 36f,
                    side * 14f, 48f,
                    side * 5f, 49f
                ),
                redMetal
            )

            drawCircle(
                color = Color(0xFFFF9CA6),
                radius = 2f * scale,
                center = point(side * 12f, 43f)
            )
        }

        // Nose ridge and lower mask.
        drawPath(
            polygon(
                -7f, 48f,
                7f, 48f,
                5f, 69f,
                0f, 78f,
                -5f, 69f
            ),
            metal
        )

        // Chin plate.
        drawPath(
            polygon(
                -12f, 72f,
                12f, 72f,
                9f, 86f,
                0f, 90f,
                -9f, 86f
            ),
            darkMetal
        )

        // Face ventilation lines.
        for (i in 0..2) {
            val y = (57f + i * 5f) * scale

            drawLine(
                Color(0xFF9CAAB6),
                point(-15f, y / scale),
                point(-7f, y / scale + 2f),
                1.1f * scale
            )

            drawLine(
                Color(0xFF9CAAB6),
                point(15f, y / scale),
                point(7f, y / scale + 2f),
                1.1f * scale
            )
        }

        // Chest plate.
        drawPath(
            polygon(
                -36f, 107f,
                0f, 117f,
                36f, 107f,
                27f, 180f,
                0f, 203f,
                -27f, 180f
            ),
            metal
        )

        drawPath(
            polygon(
                -24f, 115f,
                0f, 123f,
                24f, 115f,
                17f, 174f,
                0f, 189f,
                -17f, 174f
            ),
            darkMetal
        )

        // Red reactor chevrons.
        for (i in 0..2) {
            val y = 135f + i * 15f

            drawLine(
                RED,
                point(-17f, y),
                point(0f, y + 8f),
                3.1f * scale,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            drawLine(
                RED,
                point(0f, y + 8f),
                point(17f, y),
                3.1f * scale,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        // Shoulder reactor rings.
        for (side in listOf(-1f, 1f)) {
            val center = point(side * 70f, 109f)

            drawCircle(
                Color(0xFF111924),
                13f * scale,
                center
            )

            drawCircle(
                RED.copy(alpha = .9f),
                9f * scale,
                center,
                style = Stroke(2.2f * scale)
            )

            drawCircle(
                Color(0xFFFF9CA6),
                2.5f * scale,
                center
            )
        }

        // Metallic highlights.
        drawLine(
            Color(0xFFCED7DE),
            point(-45f, 99f),
            point(-58f, 123f),
            1.3f * scale
        )

        drawLine(
            Color(0xFFCED7DE),
            point(45f, 99f),
            point(58f, 123f),
            1.3f * scale
        )

        drawLine(
            Color(0xFF9CAAB6),
            point(0f, 17f),
            point(0f, 31f),
            1.1f * scale
        )
    }
}

@Composable
private fun UltronMessageBubble(message: ChatMessage) {

    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (message.fromUser) 18.dp else 5.dp,
        bottomEnd = if (message.fromUser) 5.dp else 18.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {

        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(shape)
                .background(
                    if (message.fromUser) {
                        Color(0xFF2A141D)
                    } else {
                        PANEL
                    }
                )
                .border(
                    1.dp,
                    if (message.fromUser) {
                        Color(0xFF783144)
                    } else {
                        Color(0xFF293644)
                    },
                    shape
                )
                .padding(horizontal = 15.dp, vertical = 12.dp)
        ) {

            Row(verticalAlignment = Alignment.CenterVertically) {

                Text(
                    if (message.fromUser) "YOU" else "ULTRON",
                    color = if (message.fromUser) {
                        Color(0xFFFFA0AD)
                    } else {
                        RED
                    },
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )

                Spacer(Modifier.weight(1f))

                Text(
                    DateFormat.format(
                        "HH:mm",
                        Date(message.createdAt)
                    ).toString(),
                    color = MUTED,
                    fontSize = 9.sp
                )
            }

            Spacer(Modifier.height(7.dp))

            Text(
                message.body,
                color = WHITE,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
        }
    }
}

@Composable
private fun UltronComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 10.dp)
            .clip(CircleShape)
            .background(PANEL)
            .border(
                1.dp,
                Color(0xFF344253),
                CircleShape
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFF202A38)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "+",
                color = MUTED,
                fontSize = 22.sp
            )
        }

        Spacer(Modifier.width(9.dp))

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
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
                            "Message ULTRON...",
                            color = MUTED,
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            }
        )

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (value.isNotBlank()) RED else Color(0xFF343944)
                )
                .clickable(enabled = value.isNotBlank()) {
                    onSend()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "➤",
                color = WHITE,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun UltronSettingsDialog(
    onDismiss: () -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151D28),
        title = {
            Text(
                "SYSTEM CONFIGURATION",
                color = WHITE,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {

                SettingsRow(
                    "CLOUD API",
                    "Not connected"
                )

                SettingsRow(
                    "AI ENGINE",
                    "Qwen3.5-9B planned"
                )

                SettingsRow(
                    "INTERFACE",
                    "Dark metallic"
                )

                SettingsRow(
                    "CHAT HISTORY",
                    "Saved on this device"
                )

                Text(
                    "No Google credentials, private model weights or API tokens are bundled in this APK.",
                    color = MUTED,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = RED)
            }
        }
    )
}

@Composable
private fun SettingsRow(
    title: String,
    value: String
) {
    Column {
        Text(
            title,
            color = MUTED,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp
        )

        Spacer(Modifier.height(4.dp))

        Text(
            value,
            color = WHITE,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
