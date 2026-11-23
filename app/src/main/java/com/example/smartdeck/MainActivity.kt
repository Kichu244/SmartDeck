package com.example.smartdeck

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import com.example.smartdeck.ui.theme.SmartDeckTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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

private val CCGlass = Color(0xFF2A3447).copy(alpha = 0.92f)
private val CCWhite = Color(0xFFF2F2F7)
private val CCDarkIcon = Color(0xFF111827)
private val CCRound = Color(0xFF3A4660)
private val PanelBg = Color(0xFF1E2840)

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
// STATE
// -----------------------------------------------------------------------------

/** Everything that belongs to the PC side. */
class DeckState {
    var volume by mutableFloatStateOf(68f)
    var brightness by mutableFloatStateOf(80f)
    var playing by mutableStateOf(true)

    var pcWifi by mutableStateOf(true)
    var pcBluetooth by mutableStateOf(true)
    var airplane by mutableStateOf(false)
    var hotspot by mutableStateOf(true)

    val flags = mutableStateMapOf<String, Boolean>()
    fun on(key: String) = flags[key] ?: false
    fun flip(key: String) { flags[key] = !on(key) }
}

/** Everything that belongs to THIS phone/tablet (read from the system). */
class MobileState {
    var batteryLevel by mutableIntStateOf(100)
    var charging by mutableStateOf(false)

    var wifiEnabled by mutableStateOf(false)
    var wifiConnected by mutableStateOf(false)
    var wifiName by mutableStateOf<String?>(null)

    var btEnabled by mutableStateOf(false)
    var btDevice by mutableStateOf<String?>(null)

    // 0 = Sound, 1 = Vibrate, 2 = Mute
    var soundMode by mutableIntStateOf(0)
}

@Suppress("DEPRECATION")
fun MobileState.refresh(ctx: Context) {
    val app = ctx.applicationContext

    // Battery
    try {
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { i ->
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING ||
                    st == BatteryManager.BATTERY_STATUS_FULL
        }
    } catch (e: Exception) { }

    // Wi-Fi (this device)
    try {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiEnabled = wm.isWifiEnabled
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        wifiConnected = wifiEnabled && caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ssid = wm.connectionInfo?.ssid?.trim('"')
        wifiName = if (wifiConnected && !ssid.isNullOrBlank() && ssid != "<unknown ssid>") ssid else null
    } catch (e: Exception) { }

    // Bluetooth (this device)
    try {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        btEnabled = adapter?.isEnabled == true
        val hasPerm = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (adapter != null && btEnabled && hasPerm) {
            queryBtDevice(app, adapter, listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) { btDevice = it }
        } else {
            btDevice = null
        }
    } catch (e: Exception) { }

    // Ringer mode (this device)
    try {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        soundMode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> 0
            AudioManager.RINGER_MODE_VIBRATE -> 1
            else -> 2
        }
    } catch (e: Exception) { }
}

@android.annotation.SuppressLint("MissingPermission")
fun queryBtDevice(ctx: Context, adapter: BluetoothAdapter, profiles: List<Int>, onResult: (String?) -> Unit) {
    if (profiles.isEmpty()) { onResult(null); return }
    val ok = adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val name = try { proxy.connectedDevices.firstOrNull()?.name } catch (e: SecurityException) { null }
            adapter.closeProfileProxy(profile, proxy)
            if (name != null) onResult(name) else queryBtDevice(ctx, adapter, profiles.drop(1), onResult)
        }
        override fun onServiceDisconnected(profile: Int) {}
    }, profiles.first())
    if (!ok) queryBtDevice(ctx, adapter, profiles.drop(1), onResult)
}

// -----------------------------------------------------------------------------
// PC TILE DEFINITIONS (add / remove entries here)
// -----------------------------------------------------------------------------
data class PcTileDef(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val isAction: Boolean = false,
    val danger: Boolean = false
)

val PcTileDefs = listOf(
    PcTileDef("wifi", "Wi-Fi", Icons.Default.Router, 2, 1),
    PcTileDef("bluetooth", "Bluetooth", Icons.Default.Bluetooth, 2, 1),
    PcTileDef("connect", "Toggles", Icons.Default.Tune, 2, 2),
    PcTileDef("brightness", "Bright", Icons.Default.BrightnessHigh, 1, 3),
    PcTileDef("volume", "Volume", Icons.Default.VolumeUp, 1, 3),
    PcTileDef("media", "Media", Icons.Default.MusicNote, 4, 2),
    PcTileDef("dark", "Dark", Icons.Default.DarkMode),
    PcTileDef("lowpower", "Saver", Icons.Default.BatterySaver),

    PcTileDef("lock", "Lock", Icons.Default.Lock, isAction = true),
    PcTileDef("sleep", "Sleep", Icons.Default.Bedtime, isAction = true),
    PcTileDef("restart", "Restart", Icons.Default.Refresh, isAction = true),
    PcTileDef("shutdown", "Shut Down", Icons.Default.PowerSettingsNew, isAction = true, danger = true),
    PcTileDef("screenshot", "Screenshot", Icons.Default.CameraAlt, isAction = true),
    PcTileDef("desktop", "Desktop", Icons.Default.DesktopWindows, isAction = true),
    PcTileDef("alttab", "Alt+Tab", Icons.Default.SwapHoriz, isAction = true),
    PcTileDef("copy", "Copy", Icons.Default.ContentCopy, isAction = true),
    PcTileDef("paste", "Paste", Icons.Default.ContentPaste, isAction = true),
    PcTileDef("browser", "Browser", Icons.Default.Public, isAction = true)
)

fun defOf(id: String): PcTileDef = PcTileDefs.first { it.id == id }

/** TODO: send the command to your PC agent (socket / HTTP / websocket). */
fun sendPcCommand(id: String) { /* TODO */ }

// -----------------------------------------------------------------------------
// GRID MODEL
// -----------------------------------------------------------------------------
data class Tile(
    val id: String,
    val col: Int,
    val row: Int,
    val colSpan: Int = 1,
    val rowSpan: Int = 1
)

const val PC_COLS = 8
const val PC_ROWS = 5
const val BAG_CAPACITY = 9

object DefaultLayouts {
    // PC page: 8 x 5
    val pc = listOf(
        Tile("wifi", 0, 0, 2, 1),
        Tile("bluetooth", 2, 0, 2, 1),
        Tile("connect", 4, 0, 2, 2),
        Tile("brightness", 6, 0, 1, 3),
        Tile("volume", 7, 0, 1, 3),
        Tile("media", 0, 1, 4, 2),
        Tile("dark", 4, 2),
        Tile("lowpower", 5, 2),

        Tile("lock", 0, 3), Tile("sleep", 1, 3), Tile("restart", 2, 3), Tile("shutdown", 3, 3),
        Tile("screenshot", 4, 3), Tile("desktop", 5, 3), Tile("alttab", 6, 3), Tile("copy", 7, 3),
        Tile("paste", 0, 4), Tile("browser", 1, 4)
    )

    val touchpad = listOf(
        Tile("pad", 0, 0, 4, 4),
        Tile("mouse", 4, 0, 2, 4)
    )
    val keyboard = listOf(Tile("keys", 0, 0, 6, 4))
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

/** Editable PC layout + the 3x3 "bag" of icons that are not on the grid. */
class PcLayoutState {
    val tiles = mutableStateListOf<Tile>().also { it.addAll(DefaultLayouts.pc) }
    val bag = mutableStateListOf<String>()
    fun reset() {
        tiles.clear(); tiles.addAll(DefaultLayouts.pc); bag.clear()
    }
}

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
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, tween(80), label = "press")
    return this
        .clickable(interactionSource = source, indication = null, onClick = onClick)
        .scale(s)
}

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
    val ctx = LocalContext.current
    val deck = remember { DeckState() }
    val mobile = remember { MobileState() }
    val layout = remember { PcLayoutState() }

    var selectedPage by remember { mutableStateOf("PC Control") }
    var editMode by remember { mutableStateOf(false) }
    var bagOpen by remember { mutableStateOf(false) }

    // Permissions needed to read the Wi-Fi name and the connected Bluetooth device
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    // Poll the real mobile configuration
    LaunchedEffect(Unit) {
        while (true) {
            mobile.refresh(ctx)
            delay(3000)
        }
    }

    val cycleSound: () -> Unit = {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val next = (mobile.soundMode + 1) % 3
        try {
            am.ringerMode = when (next) {
                0 -> AudioManager.RINGER_MODE_NORMAL
                1 -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_SILENT
            }
            mobile.soundMode = next
        } catch (e: SecurityException) {
            // Silent mode needs "Do Not Disturb access"
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) { }
        }
    }

    val goTo: (String) -> Unit = {
        editMode = false
        bagOpen = false
        selectedPage = it
    }
    val toggleEdit: () -> Unit = {
        if (!editMode) {
            selectedPage = "PC Control"
            editMode = true
        } else {
            editMode = false
        }
        bagOpen = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(IOSBgDark, Color(0xFF0F172A), Color(0xFF070A12))))
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
                    mobile = mobile,
                    selectedPage = selectedPage,
                    editMode = editMode,
                    bagOpen = bagOpen,
                    bagCount = layout.bag.size,
                    onSelectPage = goTo,
                    onToggleEdit = toggleEdit,
                    onToggleBag = { bagOpen = !bagOpen }
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
                            "PC Control" -> IOSPCControlGridDashboard(
                                deck = deck,
                                layout = layout,
                                editMode = editMode,
                                bagOpen = bagOpen,
                                onBagOpenChange = { bagOpen = it }
                            )
                            "Touchpad" -> IOSTouchpadGridDashboard()
                            "Keyboard" -> IOSKeyboardGridDashboard()
                            "Smart Home" -> IOSSmartHomeGridDashboard()
                            "Settings" -> IOSSettingsGridDashboard()
                            else -> {}
                        }
                    }
                }
            }

            // Right column: brand sits in the corner shared with the top bar
            Column(
                modifier = Modifier
                    .width(190.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BrandHeader()
                IOSRightToolbar(
                    modifier = Modifier.weight(1f),
                    mobile = mobile,
                    selectedPage = selectedPage,
                    onSelectPage = goTo,
                    onCycleSound = cycleSound
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// TOP BAR
// -----------------------------------------------------------------------------
@Composable
fun IOSTopStatusBar(
    mobile: MobileState,
    selectedPage: String,
    editMode: Boolean,
    bagOpen: Boolean,
    bagCount: Int,
    onSelectPage: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onToggleBag: () -> Unit
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
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart
        ) {
            // Normal icons fade out, the "Add icon" button fades in
            Crossfade(targetState = editMode, animationSpec = tween(250), label = "topbar") { editing ->
                if (editing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AddIconButton(bagOpen, bagCount, onToggleBag)
                        Text(
                            "Drag to move  •  corner handle to resize  •  ✕ to remove",
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TopBarActionButton(Icons.Default.Mouse, selectedPage == "Touchpad", "Touchpad") { onSelectPage("Touchpad") }
                            TopBarActionButton(Icons.Default.Keyboard, selectedPage == "Keyboard", "Virtual Keyboard") { onSelectPage("Keyboard") }
                            TopBarActionButton(Icons.Default.Dashboard, selectedPage == "PC Control", "Dashboard") { onSelectPage("PC Control") }
                        }

                        TopBarDivider()

                        IOSStatusChip(
                            if (mobile.charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            "${mobile.batteryLevel}%",
                            mobile.charging || mobile.batteryLevel > 15,
                            "Battery"
                        )

                        TopBarDivider()

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

        // Edit button is always visible
        TopBarActionButton(
            if (editMode) Icons.Default.Check else Icons.Default.Edit,
            editMode,
            "Edit layout"
        ) { onToggleEdit() }
    }
}

@Composable
fun AddIconButton(active: Boolean, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .pressable(onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) IOSTileActive else IOSTileInactive)
            .border(1.dp, if (active) TextWhite.copy(alpha = 0.5f) else IOSBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Default.Add, null, tint = TextWhite, modifier = Modifier.size(15.dp))
        Text("Add icon", color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("$count/$BAG_CAPACITY", color = if (active) TextWhite else TextMuted, fontSize = 10.sp, maxLines = 1)
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
        Icon(icon, contentDescription = tooltip, tint = if (selected) TextWhite else TextMuted, modifier = Modifier.size(15.dp))
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
            Text(text, color = TextWhite, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

// -----------------------------------------------------------------------------
// RIGHT COLUMN: BRAND + SCROLLABLE TOOLBAR
// -----------------------------------------------------------------------------
@Composable
fun BrandHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(IOSCardBg, RoundedCornerShape(16.dp))
            .border(1.dp, IOSBorder, RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.GridView, null, tint = IOSTileActive, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("SMART DECK", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
    }
}

@Composable
fun IOSRightToolbar(
    modifier: Modifier = Modifier,
    mobile: MobileState,
    selectedPage: String,
    onSelectPage: (String) -> Unit,
    onCycleSound: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
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
            // ---------------- MOBILE SYSTEM (this device) ----------------
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("MOBILE SYSTEM")

                val batteryColor = when {
                    mobile.charging -> StatusGreen
                    mobile.batteryLevel <= 15 -> StatusRed
                    mobile.batteryLevel <= 30 -> StatusOrange
                    else -> StatusGreen
                }
                IOSInfoRow(
                    icon = when {
                        mobile.charging -> Icons.Default.BatteryChargingFull
                        mobile.batteryLevel <= 15 -> Icons.Default.BatteryAlert
                        else -> Icons.Default.BatteryFull
                    },
                    text = if (mobile.charging) "${mobile.batteryLevel}% • Charging" else "${mobile.batteryLevel}%",
                    tint = batteryColor
                )

                IOSInfoRow(
                    icon = if (mobile.wifiEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
                    text = when {
                        !mobile.wifiEnabled -> "Wi-Fi off"
                        !mobile.wifiConnected -> "Not connected"
                        else -> mobile.wifiName ?: "Connected"
                    },
                    tint = if (mobile.wifiConnected) StatusGreen else StatusGrey
                )

                IOSInfoRow(
                    icon = when {
                        !mobile.btEnabled -> Icons.Default.BluetoothDisabled
                        mobile.btDevice != null -> Icons.Default.BluetoothConnected
                        else -> Icons.Default.Bluetooth
                    },
                    text = when {
                        !mobile.btEnabled -> "Bluetooth off"
                        else -> mobile.btDevice ?: "Not connected"
                    },
                    tint = if (mobile.btDevice != null) StatusGreen else StatusGrey
                )

                // Tap to cycle: Sound -> Vibrate -> Mute
                val (soundIcon, soundText, soundTint) = when (mobile.soundMode) {
                    0 -> Triple(Icons.Default.VolumeUp, "Sound", StatusGreen)
                    1 -> Triple(Icons.Default.Vibration, "Vibrate", StatusOrange)
                    else -> Triple(Icons.Default.VolumeOff, "Mute", StatusRed)
                }
                IOSInfoRow(icon = soundIcon, text = soundText, tint = soundTint, onClick = onCycleSound)
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
// 1. PC CONTROL (editable: drag, resize, remove, add from the 3x3 bag)
// -----------------------------------------------------------------------------
private fun snapMoved(t: Tile, off: Offset, sx: Float, sy: Float): Tile = t.copy(
    col = (t.col + off.x / sx).roundToInt().coerceIn(0, PC_COLS - t.colSpan),
    row = (t.row + off.y / sy).roundToInt().coerceIn(0, PC_ROWS - t.rowSpan)
)

private fun snapResized(t: Tile, off: Offset, sx: Float, sy: Float): Tile = t.copy(
    colSpan = (t.colSpan + off.x / sx).roundToInt().coerceIn(1, PC_COLS - t.col),
    rowSpan = (t.rowSpan + off.y / sy).roundToInt().coerceIn(1, PC_ROWS - t.row)
)

@Composable
fun IOSPCControlGridDashboard(
    deck: DeckState,
    layout: PcLayoutState,
    editMode: Boolean,
    bagOpen: Boolean,
    onBagOpenChange: (Boolean) -> Unit
) {
    val gap = 10.dp
    val density = LocalDensity.current

    var gridRoot by remember { mutableStateOf(Offset.Zero) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf("") }
    var bagDragId by remember { mutableStateOf<String?>(null) }
    var bagDragPos by remember { mutableStateOf(Offset.Zero) }
    var flippedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(error) {
        val e = error
        if (e != null) {
            lastError = e
            delay(3200)
            error = null
        }
    }
    LaunchedEffect(flippedId) {
        if (flippedId != null) {
            delay(3000)
            flippedId = null
        }
    }
    LaunchedEffect(editMode) {
        if (!editMode) {
            flippedId = null
            bagDragId = null
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { gridRoot = it.positionInRoot() }
    ) {
        val gapPx = with(density) { gap.toPx() }
        val cellW = (constraints.maxWidth - gapPx * (PC_COLS - 1)) / PC_COLS
        val cellH = (constraints.maxHeight - gapPx * (PC_ROWS - 1)) / PC_ROWS
        val stepX = cellW + gapPx
        val stepY = cellH + gapPx

        fun fits(c: Tile): Boolean {
            if (c.col < 0 || c.row < 0 || c.col + c.colSpan > PC_COLS || c.row + c.rowSpan > PC_ROWS) return false
            return layout.tiles.none { o ->
                o.id != c.id &&
                        c.col < o.col + o.colSpan && o.col < c.col + c.colSpan &&
                        c.row < o.row + o.rowSpan && o.row < c.row + c.rowSpan
            }
        }

        fun candidateFor(id: String, pos: Offset): Tile {
            val d = defOf(id)
            val wPx = d.colSpan * stepX - gapPx
            val hPx = d.rowSpan * stepY - gapPx
            return Tile(
                id,
                ((pos.x - wPx / 2) / stepX).roundToInt().coerceIn(0, PC_COLS - d.colSpan),
                ((pos.y - hPx / 2) / stepY).roundToInt().coerceIn(0, PC_ROWS - d.rowSpan),
                d.colSpan,
                d.rowSpan
            )
        }

        // ---------------- tiles ----------------
        layout.tiles.toList().forEach { t ->
            key(t.id) {
                EditableTileFrame(
                    tile = t,
                    editMode = editMode,
                    stepX = stepX,
                    stepY = stepY,
                    gapPx = gapPx,
                    fits = { fits(it) },
                    onCommit = { n ->
                        val i = layout.tiles.indexOfFirst { it.id == n.id }
                        if (i >= 0) layout.tiles[i] = n
                    },
                    onRemove = {
                        if (layout.bag.size >= BAG_CAPACITY) {
                            error = "Icon panel is full ($BAG_CAPACITY/$BAG_CAPACITY). Tap an icon in the panel, then ✕, to free up space."
                            onBagOpenChange(true)
                        } else {
                            layout.tiles.removeAll { it.id == t.id }
                            layout.bag.add(t.id)
                        }
                    }
                ) {
                    PcTileContent(t.id, deck)
                }
            }
        }

        // ---------------- drag from bag: target + ghost ----------------
        val dragId = bagDragId
        if (dragId != null) {
            val cand = candidateFor(dragId, bagDragPos)
            val ok = fits(cand)
            val color = if (ok) StatusGreen else StatusRed
            Box(
                Modifier
                    .offset { IntOffset((cand.col * stepX).roundToInt(), (cand.row * stepY).roundToInt()) }
                    .size(
                        width = with(density) { (cand.colSpan * stepX - gapPx).toDp() },
                        height = with(density) { (cand.rowSpan * stepY - gapPx).toDp() }
                    )
                    .background(color.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                    .border(2.dp, color, RoundedCornerShape(22.dp))
            )
            Box(
                Modifier
                    .offset { IntOffset((bagDragPos.x - 28.dp.toPx()).roundToInt(), (bagDragPos.y - 28.dp.toPx()).roundToInt()) }
                    .size(56.dp)
                    .zIndex(20f)
                    .background(IOSTileActive, RoundedCornerShape(16.dp))
                    .border(1.dp, TextWhite.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(defOf(dragId).icon, null, tint = TextWhite, modifier = Modifier.size(26.dp))
            }
        }

        // ---------------- 3x3 icon bag ----------------
        AnimatedVisibility(
            visible = editMode && bagOpen,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .zIndex(15f),
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.92f),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.92f)
        ) {
            BagPanel(
                bag = layout.bag,
                flippedId = flippedId,
                draggingId = bagDragId,
                gridRoot = { gridRoot },
                onFlip = { flippedId = it },
                onDelete = { id ->
                    layout.bag.remove(id)
                    flippedId = null
                },
                onReset = { layout.reset(); flippedId = null },
                onDragStart = { id, pos -> bagDragId = id; bagDragPos = pos },
                onDrag = { pos -> bagDragPos = pos },
                onDragEnd = {
                    val id = bagDragId
                    if (id != null) {
                        val cand = candidateFor(id, bagDragPos)
                        if (fits(cand)) {
                            layout.tiles.add(cand)
                            layout.bag.remove(id)
                        } else {
                            error = "No free space there. Drop on an empty spot, or move/shrink other icons first."
                        }
                    }
                    bagDragId = null
                }
            )
        }

        // ---------------- error popup (bottom) ----------------
        AnimatedVisibility(
            visible = error != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .zIndex(30f),
            enter = fadeIn(tween(150)) + slideInVertically { it },
            exit = fadeOut(tween(150)) + slideOutVertically { it }
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .background(Color(0xFF7F1D1D), RoundedCornerShape(14.dp))
                    .border(1.dp, StatusRed, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.ErrorOutline, null, tint = TextWhite, modifier = Modifier.size(18.dp))
                Text(lastError, color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Wraps one tile: normal when not editing; draggable, resizable and removable when editing. */
@Composable
fun EditableTileFrame(
    tile: Tile,
    editMode: Boolean,
    stepX: Float,
    stepY: Float,
    gapPx: Float,
    fits: (Tile) -> Boolean,
    onCommit: (Tile) -> Unit,
    onRemove: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val curTile by rememberUpdatedState(tile)
    val curFits by rememberUpdatedState(fits)
    val curCommit by rememberUpdatedState(onCommit)
    val curSx by rememberUpdatedState(stepX)
    val curSy by rememberUpdatedState(stepY)

    var dragOff by remember { mutableStateOf(Offset.Zero) }
    var resizeOff by remember { mutableStateOf(Offset.Zero) }
    var moving by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    val active = moving || resizing

    val baseX = tile.col * stepX
    val baseY = tile.row * stepY
    val baseW = tile.colSpan * stepX - gapPx
    val baseH = tile.rowSpan * stepY - gapPx
    val minW = stepX - gapPx
    val minH = stepY - gapPx

    // Snapped preview while dragging / resizing
    if (active) {
        val preview = if (moving) snapMoved(tile, dragOff, stepX, stepY) else snapResized(tile, resizeOff, stepX, stepY)
        val ok = fits(preview)
        val c = if (ok) StatusGreen else StatusRed
        Box(
            Modifier
                .offset { IntOffset((preview.col * stepX).roundToInt(), (preview.row * stepY).roundToInt()) }
                .size(
                    width = with(density) { (preview.colSpan * stepX - gapPx).toDp() },
                    height = with(density) { (preview.rowSpan * stepY - gapPx).toDp() }
                )
                .background(c.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                .border(2.dp, c, RoundedCornerShape(22.dp))
        )
    }

    val inf = rememberInfiniteTransition(label = "wiggle")
    val wig by inf.animateFloat(
        initialValue = -0.7f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(160, easing = LinearEasing), RepeatMode.Reverse),
        label = "wig"
    )

    Box(
        modifier = Modifier
            .offset { IntOffset((baseX + dragOff.x).roundToInt(), (baseY + dragOff.y).roundToInt()) }
            .size(
                width = with(density) { (baseW + resizeOff.x).coerceAtLeast(minW).toDp() },
                height = with(density) { (baseH + resizeOff.y).coerceAtLeast(minH).toDp() }
            )
            .zIndex(if (active) 10f else 0f)
            .graphicsLayer {
                rotationZ = if (editMode && !active) wig * (if (tile.col % 2 == 0) 1f else -1f) else 0f
                val s = if (moving) 1.05f else 1f
                scaleX = s
                scaleY = s
                alpha = if (moving) 0.9f else 1f
            }
            .then(
                if (editMode) Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { moving = true },
                        onDragEnd = {
                            val n = snapMoved(curTile, dragOff, curSx, curSy)
                            if (curFits(n)) curCommit(n)
                            dragOff = Offset.Zero
                            moving = false
                        },
                        onDragCancel = { dragOff = Offset.Zero; moving = false },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOff += amount
                        }
                    )
                } else Modifier
            )
    ) {
        content()

        if (editMode) {
            // Blocks the tile's own buttons while editing
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { } }
            )

            // Remove badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .size(22.dp)
                    .pressable(onRemove)
                    .clip(CircleShape)
                    .background(StatusRed)
                    .border(1.dp, TextWhite.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, null, tint = TextWhite, modifier = Modifier.size(13.dp))
            }

            // Resize handle
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(IOSTileActive)
                    .border(1.dp, TextWhite.copy(alpha = 0.7f), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { resizing = true },
                            onDragEnd = {
                                val n = snapResized(curTile, resizeOff, curSx, curSy)
                                if (curFits(n)) curCommit(n)
                                resizeOff = Offset.Zero
                                resizing = false
                            },
                            onDragCancel = { resizeOff = Offset.Zero; resizing = false },
                            onDrag = { change, amount ->
                                change.consume()
                                resizeOff += amount
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.OpenInFull, null, tint = TextWhite, modifier = Modifier.size(12.dp))
            }
        }
    }
}

// ---------------- 3x3 bag ----------------
@Composable
fun BagPanel(
    bag: List<String>,
    flippedId: String?,
    draggingId: String?,
    gridRoot: () -> Offset,
    onFlip: (String?) -> Unit,
    onDelete: (String) -> Unit,
    onReset: () -> Unit,
    onDragStart: (String, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .graphicsLayer { alpha = if (draggingId != null) 0.35f else 1f }
            .background(PanelBg, shape)
            .border(1.dp, IOSBorder, shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.width(3 * 48.dp + 2 * 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ICON BAG  ${bag.size}/$BAG_CAPACITY", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(
                "Reset",
                color = IOSTileActive,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.pressable(onReset)
            )
        }
        for (r in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 3) {
                    val id = bag.getOrNull(r * 3 + c)
                    if (id == null) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                                .border(1.dp, IOSBorder.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        )
                    } else {
                        key(id) {
                            BagItem(
                                def = defOf(id),
                                flipped = flippedId == id,
                                dragging = draggingId == id,
                                gridRoot = gridRoot,
                                onFlip = { onFlip(id) },
                                onDelete = { onDelete(id) },
                                onDragStart = { pos -> onDragStart(id, pos) },
                                onDrag = onDrag,
                                onDragEnd = onDragEnd
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BagItem(
    def: PcTileDef,
    flipped: Boolean,
    dragging: Boolean,
    gridRoot: () -> Offset,
    onFlip: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    var itemRoot by remember { mutableStateOf(Offset.Zero) }
    val rot by animateFloatAsState(if (flipped) 180f else 0f, tween(320), label = "flip")

    val curFlip by rememberUpdatedState(onFlip)
    val curDelete by rememberUpdatedState(onDelete)
    val curStart by rememberUpdatedState(onDragStart)
    val curDrag by rememberUpdatedState(onDrag)
    val curEnd by rememberUpdatedState(onDragEnd)
    val curGrid by rememberUpdatedState(gridRoot)

    Box(
        modifier = Modifier
            .size(48.dp)
            .onGloballyPositioned { itemRoot = it.positionInRoot() }
            .graphicsLayer {
                rotationY = rot
                cameraDistance = 12f * density
                alpha = if (dragging) 0.3f else 1f
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (rot > 90f) StatusRed else CCRound)
            .pointerInput(flipped) {
                detectTapGestures(onTap = { if (flipped) curDelete() else curFlip() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off -> curStart(itemRoot - curGrid() + off) },
                    onDragEnd = { curEnd() },
                    onDragCancel = { curEnd() },
                    onDrag = { change, _ ->
                        change.consume()
                        curDrag(itemRoot - curGrid() + change.position)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (rot <= 90f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(def.icon, null, tint = TextWhite, modifier = Modifier.size(18.dp))
                Text(def.label, color = TextMuted, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Icon(
                Icons.Default.Close, null, tint = TextWhite,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationY = 180f }
            )
        }
    }
}

// ---------------- tile contents ----------------
@Composable
fun PcTileContent(id: String, deck: DeckState) {
    when (id) {
        "wifi" -> CCLabelTile(
            Icons.Default.Router, "Wi-Fi", if (deck.pcWifi) "PC • On" else "PC • Off", deck.pcWifi
        ) { deck.pcWifi = !deck.pcWifi }

        "bluetooth" -> CCLabelTile(
            Icons.Default.Bluetooth, "Bluetooth", if (deck.pcBluetooth) "PC • On" else "PC • Off", deck.pcBluetooth
        ) { deck.pcBluetooth = !deck.pcBluetooth }

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
                    CCRoundButton(Icons.Default.ScreenLockRotation, deck.on("orient")) { deck.flip("orient") }
                    CCRoundButton(Icons.Default.DoNotDisturbOn, deck.on("dnd")) { deck.flip("dnd") }
                }
            }
        }

        "media" -> CCTile(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Track info on the left, controls on the right side
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF1E3A8A))), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = TextWhite, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Midnight City", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("M83 • Synthwave", color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MediaButton(Icons.Default.SkipPrevious, 34.dp, false) { sendPcCommand("media_prev") }
                        MediaButton(if (deck.playing) Icons.Default.Pause else Icons.Default.PlayArrow, 40.dp, true) {
                            deck.playing = !deck.playing
                            sendPcCommand("media_play_pause")
                        }
                        MediaButton(Icons.Default.SkipNext, 34.dp, false) { sendPcCommand("media_next") }
                    }
                }
                // Duration bar across the full width at the bottom
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
            value = deck.volume,
            onValueChange = { deck.volume = it },
            fill = CCWhite,
            trackColor = CCGlass,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                if (deck.volume == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null,
                tint = if (deck.volume > 12f) IOSTileActive else TextWhite,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(22.dp)
            )
        }

        "dark" -> CCIconTile(Icons.Default.DarkMode, deck.on("dark")) { deck.flip("dark") }
        "lowpower" -> CCIconTile(Icons.Default.BatterySaver, deck.on("lowpower")) { deck.flip("lowpower") }

        else -> {
            val d = PcTileDefs.firstOrNull { it.id == id }
            if (d != null && d.isAction) PCActionButton(d) { sendPcCommand(d.id) }
        }
    }
}

@Composable
fun PCActionButton(def: PcTileDef, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pressable(onClick)
            .clip(shape)
            .background(if (def.danger) StatusRed.copy(alpha = 0.85f) else CCGlass)
            .border(1.dp, IOSBorder, shape),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(def.icon, null, tint = TextWhite, modifier = Modifier.size(18.dp))
            Text(def.label, color = TextWhite, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Icon(icon, null, tint = TextWhite, modifier = Modifier.size(size * 0.55f))
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