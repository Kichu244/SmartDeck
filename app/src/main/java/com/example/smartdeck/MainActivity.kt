package com.example.smartdeck

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.example.smartdeck.ui.theme.SmartDeckTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// -----------------------------------------------------------------------------
// PALETTE
// -----------------------------------------------------------------------------
private val IOSBgDark = Color(0xFF0A0E17)
private val IOSCardBg = Color(0xFF1A2232).copy(alpha = 0.85f)
private val IOSTileInactive = Color(0xFF26334A).copy(alpha = 0.9f)
private val IOSTileActive = Color(0xFF3B82F6)
private val IOSBorder = Color(0xFF3B4A6B).copy(alpha = 0.5f)

private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val StatusGreen = Color(0xFF34D399)
private val StatusGrey = Color(0xFF64748B)
private val StatusOrange = Color(0xFFFBBF24)
private val StatusRed = Color(0xFFF87171)

// Control Center specific
private val CCGlass = Color(0xFF2A3447).copy(alpha = 0.92f)
private val CCWhite = Color(0xFFF2F2F7)
private val CCDarkIcon = Color(0xFF111827)
private val CCRound = Color(0xFF3A4660)

// -----------------------------------------------------------------------------
// ACTIVITY
// -----------------------------------------------------------------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        setContent {
            SmartDeckTheme {
                SmartDeckApp()
            }
        }
    }
}

// -----------------------------------------------------------------------------
// SHARED STATE (hoisted so values survive page switches)
// -----------------------------------------------------------------------------
class DeckState {
    var volume by mutableFloatStateOf(68f)
    var brightness by mutableFloatStateOf(80f)
    var playing by mutableStateOf(true)

    var wifi by mutableStateOf(true)
    var bluetooth by mutableStateOf(true)
    var airplane by mutableStateOf(false)
    var hotspot by mutableStateOf(true)

    // Names shown next to Wi-Fi / Bluetooth icons (wire these to real values later)
    var wifiName by mutableStateOf("Home_5G")
    var btDevice by mutableStateOf("Galaxy Buds")

    // Battery (updated from the system)
    var batteryLevel by mutableIntStateOf(100)
    var charging by mutableStateOf(false)

    // 0 = Sound, 1 = Vibrate, 2 = Mute
    var soundMode by mutableIntStateOf(0)
    val muted: Boolean get() = soundMode == 2
    fun cycleSound() { soundMode = (soundMode + 1) % 3 }

    val wifiConnected: Boolean get() = wifi && !airplane
    val btConnected: Boolean get() = bluetooth && !airplane

    // Generic on/off flags for extra tiles
    val flags = mutableStateMapOf<String, Boolean>()
    fun on(key: String) = flags[key] ?: false
    fun flip(key: String) { flags[key] = !on(key) }
}

// -----------------------------------------------------------------------------
// PC ACTION BUTTONS: add / remove entries here, the grid places them automatically
// -----------------------------------------------------------------------------
data class PcAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false
)

val PcActions = listOf(
    PcAction("lock", "Lock", Icons.Default.Lock),
    PcAction("sleep", "Sleep", Icons.Default.Bedtime),
    PcAction("restart", "Restart", Icons.Default.Refresh),
    PcAction("shutdown", "Shut Down", Icons.Default.PowerSettingsNew, danger = true),
    PcAction("screenshot", "Screenshot", Icons.Default.CameraAlt),
    PcAction("taskmgr", "Task Mgr", Icons.Default.Memory),
    PcAction("desktop", "Desktop", Icons.Default.DesktopWindows),
    PcAction("alttab", "Alt+Tab", Icons.Default.SwapHoriz),
    PcAction("copy", "Copy", Icons.Default.ContentCopy),
    PcAction("paste", "Paste", Icons.Default.ContentPaste),
    PcAction("undo", "Undo", Icons.Default.Undo),
    PcAction("explorer", "Explorer", Icons.Default.Folder),
    PcAction("browser", "Browser", Icons.Default.Public),
    PcAction("terminal", "Terminal", Icons.Default.Terminal),
    PcAction("calc", "Calculator", Icons.Default.Calculate),
    PcAction("pcsettings", "Settings", Icons.Default.Settings)
)

/** TODO: send the command to your PC agent (socket / HTTP / websocket). */
fun sendPcCommand(id: String) { /* TODO */ }

// -----------------------------------------------------------------------------
// GRID MODEL: change layout by editing these lists only
// col/row = top-left cell, colSpan/rowSpan = size in cells
// -----------------------------------------------------------------------------
data class Tile(
    val id: String,
    val col: Int,
    val row: Int,
    val colSpan: Int = 1,
    val rowSpan: Int = 1
)

object DefaultLayouts {
    // PC page: 8 columns x 5 rows (same setup as the Control Center)
    val pc: List<Tile> = listOf(
        Tile("wifi", 0, 0, 2, 1),
        Tile("bluetooth", 2, 0, 2, 1),
        Tile("connect", 4, 0, 2, 2),
        Tile("brightness", 6, 0, 1, 3),
        Tile("volume", 7, 0, 1, 3),
        Tile("media", 0, 1, 4, 2),
        Tile("dark", 4, 2, 1, 1),
        Tile("lowpower", 5, 2, 1, 1)
    ) + PcActions.mapIndexed { i, a -> Tile(a.id, i % 8, 3 + i / 8) }

    val touchpad = listOf(
        Tile("pad", 0, 0, 4, 4),
        Tile("mouse", 4, 0, 2, 4)
    )
    val keyboard = listOf(
        Tile("keys", 0, 0, 6, 4)
    )
    // 6 columns x 5 rows
    val home = listOf(
        Tile("rooms", 0, 0, 6, 1),
        Tile("light", 0, 1, 2, 2),
        Tile("fan", 2, 1, 2, 2),
        Tile("plug", 4, 1, 2, 2),
        Tile("ac", 0, 3, 4, 2),
        Tile("esp", 4, 3, 2, 2)
    )
    val settings = listOf(
        Tile("display", 0, 0, 3, 4),
        Tile("connection", 3, 0, 3, 4)
    )
}

/**
 * Fixed grid that always fits the available space (no scrolling, no clipping).
 * Cell size is computed from the real size, so it adapts to any screen.
 */
@Composable
fun TileGrid(
    columns: Int,
    rows: Int,
    tiles: List<Tile>,
    modifier: Modifier = Modifier,
    gap: Dp = 10.dp,
    content: @Composable BoxScope.(Tile) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cellW = (maxWidth - gap * (columns - 1)) / columns
        val cellH = (maxHeight - gap * (rows - 1)) / rows
        tiles.forEach { t ->
            key(t.id) {
                Box(
                    modifier = Modifier
                        .offset(x = (cellW + gap) * t.col, y = (cellH + gap) * t.row)
                        .size(
                            width = cellW * t.colSpan + gap * (t.colSpan - 1),
                            height = cellH * t.rowSpan + gap * (t.rowSpan - 1)
                        )
                ) {
                    content(t)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// TOUCH HELPERS
// -----------------------------------------------------------------------------
/** Whole-area tap target with instant pressed feedback (scale-down). */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, tween(80), label = "press")
    return this
        .clickable(interactionSource = source, indication = null, onClick = onClick)
        .scale(s)
}

/** Vertical slider: tap or drag anywhere on the pill, responds instantly. */
@Composable
fun VerticalPillSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    fill: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    trackColor: Color = IOSTileInactive,
    content: @Composable BoxScope.() -> Unit = {}
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    val onChange by rememberUpdatedState(onValueChange)
    fun yToValue(y: Float): Float = (1f - y / heightPx).coerceIn(0f, 1f) * 100f

    Box(
        modifier = modifier
            .clip(shape)
            .background(trackColor)
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { off -> onChange(yToValue(off.y)) })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { off -> onChange(yToValue(off.y)) }
                ) { change, _ ->
                    change.consume()
                    onChange(yToValue(change.position.y))
                }
            }
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight((value / 100f).coerceIn(0.001f, 1f))
                .background(fill)
        )
        content()
    }
}

/** Ticks every second so the clock stays live. */
@Composable
fun rememberNow(): Date {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    return now
}

// -----------------------------------------------------------------------------
// APP ROOT
// -----------------------------------------------------------------------------
@Composable
fun SmartDeckApp() {
    val deck = remember { DeckState() }
    val ctx = LocalContext.current
    var selectedPage by remember { mutableStateOf("PC Control") }
    var pcConnected by remember { mutableStateOf(true) }

    // Real battery level + charging state
    LaunchedEffect(Unit) {
        while (true) {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) deck.batteryLevel = level * 100 / scale
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                deck.charging = st == BatteryManager.BATTERY_STATUS_CHARGING ||
                        st == BatteryManager.BATTERY_STATUS_FULL
            }
            delay(5000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(IOSBgDark, Color(0xFF0F172A), Color(0xFF070A12))
                )
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IOSTopStatusBar(
                    deck = deck,
                    selectedPage = selectedPage,
                    pcConnected = pcConnected,
                    onSelectPage = { selectedPage = it },
                    onToggleConnection = { pcConnected = !pcConnected }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AnimatedContent(
                        targetState = selectedPage,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(150)) togetherWith
                                    fadeOut(animationSpec = tween(100))
                        },
                        label = "GridPageTransition"
                    ) { page ->
                        when (page) {
                            "PC Control" -> IOSPCControlGridDashboard(deck)
                            "Touchpad" -> IOSTouchpadGridDashboard()
                            "Keyboard" -> IOSKeyboardGridDashboard()
                            "Smart Home" -> IOSSmartHomeGridDashboard()
                            "Settings" -> IOSSettingsGridDashboard()
                            else -> {}
                        }
                    }
                }
            }

            IOSRightToolbar(
                deck = deck,
                selectedPage = selectedPage,
                onSelectPage = { selectedPage = it }
            )
        }
    }
}

// -----------------------------------------------------------------------------
// TOP STATUS BAR
// -----------------------------------------------------------------------------
@Composable
fun IOSTopStatusBar(
    deck: DeckState,
    selectedPage: String,
    pcConnected: Boolean,
    onSelectPage: (String) -> Unit,
    onToggleConnection: () -> Unit
) {
    val now = rememberNow()
    val timeFmt = remember { SimpleDateFormat("hh:mm:ss a", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(IOSCardBg, RoundedCornerShape(16.dp))
            .border(1.dp, IOSBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Brand
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxHeight()
                .pressable(onToggleConnection)
        ) {
            Icon(Icons.Default.GridView, null, tint = IOSTileActive, modifier = Modifier.size(18.dp))
            Text("SMART DECK", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
        }

        // Right side (scrolls horizontally if the screen is too narrow)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Page shortcuts
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TopBarActionButton(Icons.Default.Mouse, selectedPage == "Touchpad", "Touchpad") { onSelectPage("Touchpad") }
                    TopBarActionButton(Icons.Default.Keyboard, selectedPage == "Keyboard", "Virtual Keyboard") { onSelectPage("Keyboard") }
                    TopBarActionButton(Icons.Default.Dashboard, selectedPage == "PC Control", "Dashboard") { onSelectPage("PC Control") }
                }

                TopBarDivider()

                // Connection status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IOSStatusChip(Icons.Default.Computer, null, pcConnected, "PC Link")
                    IOSStatusChip(
                        if (deck.charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                        "${deck.batteryLevel}%", deck.charging || deck.batteryLevel > 15, "Battery"
                    )
                    IOSStatusChip(
                        if (deck.wifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        if (deck.wifiConnected) deck.wifiName else null,
                        deck.wifiConnected, "Wi-Fi"
                    )
                    IOSStatusChip(
                        if (deck.btConnected) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                        if (deck.btConnected) deck.btDevice else null,
                        deck.btConnected, "Bluetooth"
                    )
                }

                TopBarDivider()

                // Time + date
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.AccessTime, null, tint = StatusGreen, modifier = Modifier.size(15.dp))
                        Text(timeFmt.format(now), color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.CalendarToday, null, tint = StatusGreen, modifier = Modifier.size(15.dp))
                        Text(dateFmt.format(now), color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun TopBarDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(20.dp)
            .background(IOSBorder)
    )
}

@Composable
fun TopBarActionButton(
    icon: ImageVector,
    selected: Boolean,
    tooltip: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .pressable(onClick)
            .clip(CircleShape)
            .background(if (selected) IOSTileActive else IOSTileInactive)
            .border(1.dp, if (selected) TextWhite.copy(alpha = 0.5f) else IOSBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tooltip,
            tint = if (selected) TextWhite else TextMuted,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
fun IOSStatusChip(icon: ImageVector, text: String?, active: Boolean, tooltip: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(if (active) StatusGreen.copy(alpha = 0.2f) else StatusGrey.copy(alpha = 0.1f), CircleShape)
                .border(1.dp, if (active) StatusGreen.copy(alpha = 0.5f) else StatusGrey.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = tooltip, tint = if (active) StatusGreen else StatusGrey, modifier = Modifier.size(14.dp))
        }
        if (text != null) {
            Text(
                text,
                color = TextWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 90.dp)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// RIGHT TOOLBAR (scrollable)
// -----------------------------------------------------------------------------
@Composable
fun IOSRightToolbar(
    deck: DeckState,
    selectedPage: String,
    onSelectPage: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .width(190.dp)
            .fillMaxHeight()
            .background(IOSCardBg, RoundedCornerShape(22.dp))
            .border(1.dp, IOSBorder, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------------- MOBILE SYSTEM ----------------
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("MOBILE SYSTEM")

                val batteryColor = when {
                    deck.charging -> StatusGreen
                    deck.batteryLevel <= 15 -> StatusRed
                    deck.batteryLevel <= 30 -> StatusOrange
                    else -> StatusGreen
                }
                IOSInfoRow(
                    icon = if (deck.charging) Icons.Default.BatteryChargingFull
                    else if (deck.batteryLevel <= 15) Icons.Default.BatteryAlert
                    else Icons.Default.BatteryFull,
                    text = if (deck.charging) "${deck.batteryLevel}% • Charging" else "${deck.batteryLevel}%",
                    tint = batteryColor
                )

                IOSInfoRow(
                    icon = if (deck.wifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                    text = if (deck.wifiConnected) deck.wifiName else "Wi-Fi off",
                    tint = if (deck.wifiConnected) StatusGreen else StatusGrey
                )

                IOSInfoRow(
                    icon = if (deck.btConnected) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                    text = if (deck.btConnected) deck.btDevice else "Bluetooth off",
                    tint = if (deck.btConnected) StatusGreen else StatusGrey
                )

                // Tap to cycle: Sound -> Vibrate -> Mute
                val (soundIcon, soundText, soundTint) = when (deck.soundMode) {
                    0 -> Triple(Icons.Default.VolumeUp, "Sound", StatusGreen)
                    1 -> Triple(Icons.Default.Vibration, "Vibrate", StatusOrange)
                    else -> Triple(Icons.Default.VolumeOff, "Mute", StatusRed)
                }
                IOSInfoRow(
                    icon = soundIcon,
                    text = soundText,
                    tint = soundTint,
                    onClick = { deck.cycleSound() }
                )
            }

            // ---------------- CONTROL MODULES ----------------
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("CONTROL MODULES")
                IOSNavRow("PC Control", Icons.Default.Computer, selectedPage == "PC Control") { onSelectPage("PC Control") }
                IOSNavRow("Smart Home", Icons.Default.Home, selectedPage == "Smart Home") { onSelectPage("Smart Home") }
                IOSNavRow("Settings", Icons.Default.Settings, selectedPage == "Settings") { onSelectPage("Settings") }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
fun IOSInfoRow(
    icon: ImageVector,
    text: String,
    tint: Color,
    onClick: (() -> Unit)? = null
) {
    val base = if (onClick != null) Modifier.pressable(onClick) else Modifier
    Row(
        modifier = base
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .background(IOSTileInactive, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text,
            color = TextWhite,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun IOSNavRow(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .pressable(onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) IOSTileActive else IOSTileInactive)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = if (selected) TextWhite else TextMuted, modifier = Modifier.size(18.dp))
        Text(title, color = if (selected) TextWhite else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// -----------------------------------------------------------------------------
// 1. PC CONTROL (same setup as the Control Center + PC buttons)
// -----------------------------------------------------------------------------
@Composable
fun IOSPCControlGridDashboard(deck: DeckState) {
    val tiles = remember { DefaultLayouts.pc }

    TileGrid(columns = 8, rows = 5, tiles = tiles) { t ->
        when (t.id) {
            "wifi" -> CCLabelTile(
                if (deck.wifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                "Wi-Fi",
                if (deck.wifiConnected) deck.wifiName else "Off",
                deck.wifiConnected
            ) { deck.wifi = !deck.wifi }

            "bluetooth" -> CCLabelTile(
                if (deck.btConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                "Bluetooth",
                if (deck.btConnected) deck.btDevice else "Off",
                deck.btConnected
            ) { deck.bluetooth = !deck.bluetooth }

            "connect" -> CCTile(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CCRoundButton(Icons.Default.AirplanemodeActive, deck.airplane) { deck.airplane = !deck.airplane }
                        CCRoundButton(Icons.Default.WifiTethering, deck.hotspot) { deck.hotspot = !deck.hotspot }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CCRoundButton(Icons.Default.Wifi, deck.wifi) { deck.wifi = !deck.wifi }
                        CCRoundButton(Icons.Default.Bluetooth, deck.bluetooth) { deck.bluetooth = !deck.bluetooth }
                    }
                }
            }

            "media" -> CCTile(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF1E3A8A))), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MusicNote, null, tint = TextWhite, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Midnight City", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("M83 • Synthwave", color = TextMuted, fontSize = 10.sp, maxLines = 1)
                        }
                        Icon(Icons.Default.Cast, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        LinearProgressIndicator(
                            progress = { 0.35f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = CCWhite,
                            trackColor = IOSTileInactive
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1:24", color = TextMuted, fontSize = 8.sp)
                            Text("-2:36", color = TextMuted, fontSize = 8.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        MediaButton(Icons.Default.SkipPrevious, 38.dp, false) { sendPcCommand("media_prev") }
                        MediaButton(if (deck.playing) Icons.Default.Pause else Icons.Default.PlayArrow, 38.dp, false) {
                            deck.playing = !deck.playing
                            sendPcCommand("media_play_pause")
                        }
                        MediaButton(Icons.Default.SkipNext, 38.dp, false) { sendPcCommand("media_next") }
                    }
                }
            }

            "brightness" -> VerticalPillSlider(
                value = deck.brightness,
                onValueChange = { deck.brightness = it },
                fill = CCWhite,
                trackColor = CCGlass,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    Icons.Default.BrightnessHigh, null,
                    tint = if (deck.brightness > 12f) CCDarkIcon else TextWhite,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(22.dp)
                )
            }

            "volume" -> VerticalPillSlider(
                value = if (deck.muted) 0f else deck.volume,
                onValueChange = { deck.volume = it; if (deck.muted) deck.soundMode = 0 },
                fill = CCWhite,
                trackColor = CCGlass,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    when {
                        deck.muted || deck.volume == 0f -> Icons.Default.VolumeOff
                        deck.soundMode == 1 -> Icons.Default.Vibration
                        else -> Icons.Default.VolumeUp
                    }, null,
                    tint = if (!deck.muted && deck.volume > 12f) IOSTileActive else TextWhite,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(22.dp)
                )
            }

            "dark" -> CCIconTile(Icons.Default.DarkMode, deck.on("dark")) { deck.flip("dark") }
            "lowpower" -> CCIconTile(Icons.Default.BatteryChargingFull, deck.on("lowpower")) { deck.flip("lowpower") }

            else -> {
                val action = PcActions.firstOrNull { it.id == t.id }
                if (action != null) PCActionButton(action) { sendPcCommand(action.id) }
            }
        }
    }
}

@Composable
fun PCActionButton(action: PcAction, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pressable(onClick)
            .clip(shape)
            .background(if (action.danger) StatusRed.copy(alpha = 0.85f) else CCGlass)
            .border(1.dp, IOSBorder, shape),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(action.icon, null, tint = TextWhite, modifier = Modifier.size(18.dp))
            Text(
                action.label,
                color = TextWhite,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MediaButton(icon: ImageVector, size: Dp, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .pressable(onClick)
            .clip(CircleShape)
            .background(if (filled) IOSTileActive else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = TextWhite, modifier = Modifier.size(20.dp))
    }
}

// -----------------------------------------------------------------------------
// CONTROL CENTER STYLE TILES
// -----------------------------------------------------------------------------
@Composable
fun CCTile(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    shape: Shape = RoundedCornerShape(22.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val base = if (onClick != null) modifier.pressable(onClick) else modifier
    Box(
        modifier = base
            .clip(shape)
            .background(if (active) CCWhite else CCGlass)
            .border(1.dp, IOSBorder, shape),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun CCIconTile(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    CCTile(Modifier.fillMaxSize(), active, CircleShape, onClick) {
        Icon(icon, null, tint = if (active) CCDarkIcon else TextWhite, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun CCLabelTile(icon: ImageVector, title: String, subtitle: String?, active: Boolean, onClick: () -> Unit) {
    CCTile(Modifier.fillMaxSize(), active, RoundedCornerShape(22.dp), onClick) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, null, tint = if (active) IOSTileActive else TextWhite, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = if (active) CCDarkIcon else TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = if (active) StatusGrey else TextMuted,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun CCRoundButton(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .pressable(onClick)
            .clip(CircleShape)
            .background(if (active) IOSTileActive else CCRound),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = TextWhite, modifier = Modifier.size(22.dp))
    }
}

// -----------------------------------------------------------------------------
// 2. TOUCHPAD
// -----------------------------------------------------------------------------
@Composable
fun IOSTouchpadGridDashboard() {
    var gestureStatus by remember { mutableStateOf("Ready (1 Tap = Click | 2 Taps = Enter | Hold = Drag)") }
    val tiles = remember { DefaultLayouts.touchpad }

    TileGrid(columns = 6, rows = 4, tiles = tiles) { t ->
        when (t.id) {
            "pad" -> IOSGridSquircle {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(IOSTileInactive, RoundedCornerShape(16.dp))
                        .border(1.dp, IOSTileActive.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { gestureStatus = "1 Tap ➔ Left Click" },
                                onDoubleTap = { gestureStatus = "2 Taps ➔ Double-Click" },
                                onLongPress = { gestureStatus = "Hold ➔ Select / Drag Mode" }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.TouchApp, null, tint = IOSTileActive, modifier = Modifier.size(36.dp))
                        Text("Touchpad Surface Area", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(gestureStatus, color = StatusGreen, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            "mouse" -> IOSGridSquircle {
                Text("MOUSE ACTIONS", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IOSActionTile("Left Click", Icons.Default.Mouse, IOSTileActive, Modifier.fillMaxWidth().weight(1f)) { gestureStatus = "Left Click pressed" }
                    IOSActionTile("Right Click", Icons.Default.Mouse, IOSTileInactive, Modifier.fillMaxWidth().weight(1f)) { gestureStatus = "Right Click triggered" }
                    IOSActionTile("Scroll", Icons.Default.SwapVert, IOSTileInactive, Modifier.fillMaxWidth().weight(1f)) { gestureStatus = "Scroll active" }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 3. VIRTUAL KEYBOARD
// -----------------------------------------------------------------------------
@Composable
fun IOSKeyboardGridDashboard() {
    var textInput by remember { mutableStateOf("") }
    var macroFeedback by remember { mutableStateOf("Virtual Keyboard Ready") }
    val tiles = remember { DefaultLayouts.keyboard }
    val macros = listOf("Ctrl+C", "Ctrl+V", "Ctrl+A", "Win+D", "Alt+Tab", "Esc", "Enter", "Back")

    TileGrid(columns = 6, rows = 4, tiles = tiles) { _ ->
        IOSGridSquircle {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VIRTUAL KEYBOARD & MACRO SENDER", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Type text to send directly to PC...", color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IOSTileActive,
                        unfocusedBorderColor = IOSBorder,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = IOSTileInactive,
                        unfocusedContainerColor = IOSTileInactive
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { macroFeedback = "Sent: '$textInput'"; textInput = "" })
                )
                Text(macroFeedback, color = StatusGreen, fontSize = 10.sp, maxLines = 1)
                Text("QUICK SHORTCUT MACROS", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                macros.chunked(4).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { macro ->
                            IOSActionTile(macro, null, IOSTileInactive, Modifier.weight(1f).fillMaxHeight()) {
                                macroFeedback = "Executed Macro: $macro"
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 4. SMART HOME
// -----------------------------------------------------------------------------
@Composable
fun IOSSmartHomeGridDashboard() {
    var selectedRoom by remember { mutableStateOf("Living Room") }
    var lightOn by remember { mutableStateOf(true) }
    var fanOn by remember { mutableStateOf(false) }
    var plugOn by remember { mutableStateOf(true) }
    var acOn by remember { mutableStateOf(false) }
    var acTemp by remember { mutableFloatStateOf(22f) }
    val tiles = remember { DefaultLayouts.home }

    TileGrid(columns = 6, rows = 5, tiles = tiles) { t ->
        when (t.id) {
            "rooms" -> Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("Living Room", "Bedroom", "Office", "Kitchen").forEach { room ->
                    IOSRoomChip(room, selectedRoom == room) { selectedRoom = room }
                }
            }

            "light" -> IOSGridSquircle { IOSGridDeviceContent("Ceiling Light", selectedRoom, Icons.Default.Lightbulb, lightOn) { lightOn = it } }
            "fan" -> IOSGridSquircle { IOSGridDeviceContent("ESP32 Fan", selectedRoom, Icons.Default.AcUnit, fanOn) { fanOn = it } }
            "plug" -> IOSGridSquircle { IOSGridDeviceContent("Power Plug", selectedRoom, Icons.Default.Power, plugOn) { plugOn = it } }

            "ac" -> IOSGridSquircle {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Inverter AC Unit", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(if (acOn) "Cooling • Fan Auto" else "Off", color = if (acOn) IOSTileActive else TextMuted, fontSize = 11.sp)
                        Text("Target: ${acTemp.toInt()}°C", color = TextMuted, fontSize = 10.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IOSActionTile("", Icons.Default.Remove, IOSTileInactive, Modifier.size(40.dp)) { if (acTemp > 16f) acTemp -= 1f }
                        Text("${acTemp.toInt()}°", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        IOSActionTile("", Icons.Default.Add, IOSTileInactive, Modifier.size(40.dp)) { if (acTemp < 30f) acTemp += 1f }
                        Switch(
                            checked = acOn,
                            onCheckedChange = { acOn = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = TextWhite, checkedTrackColor = IOSTileActive)
                        )
                    }
                }
            }

            "esp" -> IOSGridSquircle {
                Text("ESP32 NODE STATUS", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("IP", color = TextMuted, fontSize = 10.sp)
                    Text("192.168.1.145", color = TextWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("RSSI", color = TextMuted, fontSize = 10.sp)
                    Text("-54 dBm", color = StatusGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 5. SETTINGS
// -----------------------------------------------------------------------------
@Composable
fun IOSSettingsGridDashboard() {
    var idleClock by remember { mutableStateOf(true) }
    var batterySaver by remember { mutableStateOf(true) }
    var reduceAnimations by remember { mutableStateOf(false) }
    var keepScreenAwake by remember { mutableStateOf(true) }
    var pcHostname by remember { mutableStateOf("192.168.1.108") }
    val tiles = remember { DefaultLayouts.settings }

    TileGrid(columns = 6, rows = 4, tiles = tiles) { t ->
        when (t.id) {
            "display" -> IOSGridSquircle {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DISPLAY & POWER", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    IOSSettingRow("Idle Clock Screen Saver", idleClock) { idleClock = it }
                    IOSSettingRow("Battery Saver", batterySaver) { batterySaver = it }
                    IOSSettingRow("Reduce Animations", reduceAnimations) { reduceAnimations = it }
                    IOSSettingRow("Keep Screen Awake", keepScreenAwake) { keepScreenAwake = it }
                }
            }

            "connection" -> IOSGridSquircle {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("PC CONNECTION", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = pcHostname,
                        onValueChange = { pcHostname = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IOSTileActive,
                            unfocusedBorderColor = IOSBorder,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedContainerColor = IOSTileInactive,
                            unfocusedContainerColor = IOSTileInactive
                        ),
                        singleLine = true
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Smart Deck v1.0", color = TextMuted, fontSize = 10.sp)
                        IOSActionTile("Reset", Icons.Default.Restore, StatusRed.copy(alpha = 0.3f)) { /* TODO */ }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// SHARED COMPONENTS
// -----------------------------------------------------------------------------
@Composable
fun IOSGridSquircle(
    modifier: Modifier = Modifier.fillMaxSize(),
    padding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(IOSCardBg, RoundedCornerShape(22.dp))
            .border(1.dp, IOSBorder, RoundedCornerShape(22.dp))
            .padding(padding),
        verticalArrangement = Arrangement.SpaceBetween,
        content = content
    )
}

@Composable
fun IOSGridDeviceContent(
    title: String,
    room: String,
    icon: ImageVector,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pressable { onToggle(!isOn) },
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(if (isOn) IOSTileActive else IOSTileInactive, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = TextWhite, modifier = Modifier.size(18.dp))
            }
            Switch(
                checked = isOn,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = TextWhite, checkedTrackColor = IOSTileActive),
                modifier = Modifier.scale(0.8f)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(room, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(title, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (isOn) "Active" else "Off", color = if (isOn) IOSTileActive else TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
fun IOSRoomChip(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .pressable(onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) IOSTileActive else IOSTileInactive)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) TextWhite else TextMuted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun IOSActionTile(
    title: String,
    icon: ImageVector?,
    bgColor: Color,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .pressable(onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        if (vertical) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (icon != null) Icon(icon, null, tint = TextWhite, modifier = Modifier.size(20.dp))
                if (title.isNotEmpty()) Text(title, color = TextWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) Icon(icon, null, tint = TextWhite, modifier = Modifier.size(16.dp))
                if (icon != null && title.isNotEmpty()) Spacer(modifier = Modifier.width(6.dp))
                if (title.isNotEmpty()) Text(title, color = TextWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
fun IOSSettingRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable { onCheckedChange(!checked) }
            .background(IOSTileInactive, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = TextWhite, checkedTrackColor = IOSTileActive),
            modifier = Modifier.scale(0.8f)
        )
    }
}