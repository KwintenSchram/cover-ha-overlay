package com.haoverlay.coverscreen.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.haoverlay.coverscreen.R
import com.haoverlay.coverscreen.data.model.*
import com.haoverlay.coverscreen.data.storage.SecureConfigManager
import com.haoverlay.coverscreen.network.ConnectionTestResult
import com.haoverlay.coverscreen.network.HaResult
import com.haoverlay.coverscreen.network.HomeAssistantClient
import com.haoverlay.coverscreen.service.CoverOverlayService
import com.haoverlay.coverscreen.service.display.CoverDisplayManager
import com.haoverlay.coverscreen.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var configManager: SecureConfigManager
    private lateinit var coverDisplayManager: CoverDisplayManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = SecureConfigManager.getInstance(this)
        coverDisplayManager = CoverDisplayManager(this)
        coverDisplayManager.start()

        setContent {
            CoverOverlayTheme {
                MainScreen(
                    configManager = configManager,
                    coverDisplayManager = coverDisplayManager,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        coverDisplayManager.refreshDisplays()
    }

    override fun onDestroy() {
        super.onDestroy()
        coverDisplayManager.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    configManager: SecureConfigManager,
    coverDisplayManager: CoverDisplayManager,
    onRequestNotificationPermission: () -> Unit
) {
    val context = LocalContext.current

    val haConfig by configManager.haConfigFlow.collectAsState()
    val buttons by configManager.buttonsFlow.collectAsState()
    val settings by configManager.settingsFlow.collectAsState()
    val displays by coverDisplayManager.displaysFlow.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var isPreviewActive by remember { mutableStateOf(false) }

    var editingButton by remember { mutableStateOf<OverlayButtonConfig?>(null) }
    var showButtonDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_sparkles),
                            contentDescription = null,
                            tint = AccentAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cover HA Overlay",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        isPreviewActive = !isPreviewActive
                        CoverOverlayService.toggleTestPreview(context, isPreviewActive)
                        Toast.makeText(
                            context,
                            if (isPreviewActive) "Overlay Preview ON" else "Overlay Preview OFF",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            imageVector = if (isPreviewActive) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Test Overlay Preview",
                            tint = if (isPreviewActive) AccentGreen else TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = DarkSurface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Controls") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.SettingsRemote, contentDescription = null) },
                    label = { Text("Buttons") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Layout") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                    label = { Text("Displays") }
                )
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                0 -> DashboardTab(
                    configManager = configManager,
                    haConfig = haConfig,
                    settings = settings,
                    onRequestNotificationPermission = onRequestNotificationPermission
                )
                1 -> ButtonsTab(
                    buttons = buttons,
                    haConfig = haConfig,
                    onAddButtonClick = {
                        editingButton = null
                        showButtonDialog = true
                    },
                    onEditButtonClick = { button ->
                        editingButton = button
                        showButtonDialog = true
                    },
                    onDeleteButtonClick = { buttonId ->
                        configManager.removeButton(buttonId)
                    },
                    onMoveUp = { index ->
                        if (index > 0) {
                            val list = buttons.toMutableList()
                            val item = list.removeAt(index)
                            list.add(index - 1, item)
                            configManager.reorderButtons(list)
                        }
                    },
                    onMoveDown = { index ->
                        if (index < buttons.size - 1) {
                            val list = buttons.toMutableList()
                            val item = list.removeAt(index)
                            list.add(index + 1, item)
                            configManager.reorderButtons(list)
                        }
                    }
                )
                2 -> LayoutTab(
                    settings = settings,
                    onSaveSettings = { newSettings ->
                        configManager.saveOverlaySettings(newSettings)
                    }
                )
                3 -> DisplaysTab(
                    displays = displays,
                    settings = settings,
                    onSelectDisplayMode = { mode, specificId ->
                        configManager.saveOverlaySettings(
                            settings.copy(targetDisplayMode = mode, targetDisplayId = specificId)
                        )
                    }
                )
            }
        }

        if (showButtonDialog) {
            ButtonEditDialog(
                initialButton = editingButton,
                haConfig = haConfig,
                onDismiss = { showButtonDialog = false },
                onSave = { savedButton ->
                    if (editingButton == null) {
                        configManager.addButton(savedButton)
                    } else {
                        configManager.updateButton(savedButton)
                    }
                    showButtonDialog = false
                }
            )
        }
    }
}

@Composable
fun DashboardTab(
    configManager: SecureConfigManager,
    haConfig: HaConfig,
    settings: OverlaySettings,
    onRequestNotificationPermission: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var baseUrlInput by remember(haConfig) { mutableStateOf(haConfig.baseUrl) }
    var tokenInput by remember(haConfig) { mutableStateOf(haConfig.accessToken) }
    var isTokenVisible by remember { mutableStateOf(false) }

    var testStatus by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isBatteryIgnoring by remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Service Status Banner
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (settings.isServiceEnabled) AccentGreen else AccentRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (settings.isServiceEnabled) "Overlay Service Running" else "Overlay Service Stopped",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = if (settings.isServiceEnabled)
                                "Overlay attaches automatically when cover screen turns on."
                            else
                                "Service is disabled. Enable switch to start overlay.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Switch(
                        checked = settings.isServiceEnabled,
                        onCheckedChange = { enabled ->
                            configManager.setServiceEnabled(enabled)
                            if (enabled) {
                                CoverOverlayService.start(context)
                            } else {
                                CoverOverlayService.stop(context)
                            }
                        }
                    )
                }
            }
        }

        // Permissions Center
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "System Permissions",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Overlay Permission
                    PermissionRow(
                        title = "Draw Over Other Apps",
                        description = "Required to render the quick-control bar on the cover screen.",
                        isGranted = hasOverlayPermission,
                        onGrantClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider(color = DarkCardBorder, modifier = Modifier.padding(vertical = 8.dp))

                    // Battery Optimization
                    PermissionRow(
                        title = "Unrestricted Battery",
                        description = "Prevents Samsung One UI from killing the overlay background service.",
                        isGranted = isBatteryIgnoring,
                        onGrantClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider(color = DarkCardBorder, modifier = Modifier.padding(vertical = 8.dp))

                    // Notifications
                    PermissionRow(
                        title = "Notifications",
                        description = "Enables foreground service persistent status notification.",
                        isGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                        onGrantClick = onRequestNotificationPermission
                    )
                }
            }
        }

        // Home Assistant Connection Config
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Home Assistant Setup",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        label = { Text("Server URL (e.g. http://192.168.1.50:8123)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = DarkCardBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Long-Lived Access Token") },
                        singleLine = true,
                        visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                                Icon(
                                    imageVector = if (isTokenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = DarkCardBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val updated = haConfig.copy(
                                    baseUrl = baseUrlInput.trim(),
                                    accessToken = tokenInput.trim()
                                )
                                configManager.saveHaConfig(updated)
                                Toast.makeText(context, "HA Settings Saved", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Text("Save Config")
                        }

                        OutlinedButton(
                            onClick = {
                                isTestingConnection = true
                                testStatus = null
                                coroutineScope.launch {
                                    val client = HomeAssistantClient(
                                        haConfig.copy(baseUrl = baseUrlInput, accessToken = tokenInput)
                                    )
                                    val res = client.testConnection()
                                    testStatus = res
                                    isTestingConnection = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isTestingConnection
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Test Connection")
                            }
                        }
                    }

                    testStatus?.let { res ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (res.isSuccess) Color(0x2210B981) else Color(0x22EF4444))
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = if (res.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (res.isSuccess) AccentGreen else AccentRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (res.isSuccess)
                                    "Connected! (${res.latencyMs}ms) HA Version: ${res.serverVersion ?: "Unknown"}"
                                else
                                    res.message,
                                fontSize = 12.sp,
                                color = if (res.isSuccess) AccentGreen else AccentRed
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ButtonsTab(
    buttons: List<OverlayButtonConfig>,
    haConfig: HaConfig,
    onAddButtonClick: () -> Unit,
    onEditButtonClick: (OverlayButtonConfig) -> Unit,
    onDeleteButtonClick: (String) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Quick Action Buttons (${buttons.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = TextPrimary
            )

            Button(
                onClick = onAddButtonClick,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Button")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (buttons.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No quick-control buttons configured.\nTap '+ Add Button' to configure your Home Assistant entities.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(buttons) { index, button ->
                    ButtonCard(
                        button = button,
                        index = index,
                        totalCount = buttons.size,
                        onEdit = { onEditButtonClick(button) },
                        onDelete = { onDeleteButtonClick(button.id) },
                        onMoveUp = { onMoveUp(index) },
                        onMoveDown = { onMoveDown(index) }
                    )
                }
            }
        }
    }
}

@Composable
fun ButtonCard(
    button: OverlayButtonConfig,
    index: Int,
    totalCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val iconRes = resolveDrawableResource(button.iconName)
                val tint = parseColorSafely(button.customColorHex) ?: AccentAmber
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = button.label.ifBlank { button.entityId },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
                Text(
                    text = "${button.domain}.${button.service} → ${button.entityId}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            IconButton(onClick = onMoveUp, enabled = index > 0) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = "Move Up",
                    tint = if (index > 0) TextSecondary else TextMuted
                )
            }
            IconButton(onClick = onMoveDown, enabled = index < totalCount - 1) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = "Move Down",
                    tint = if (index < totalCount - 1) TextSecondary else TextMuted
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = PrimaryBlue)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AccentRed)
            }
        }
    }
}

@Composable
fun LayoutTab(
    settings: OverlaySettings,
    onSaveSettings: (OverlaySettings) -> Unit
) {
    var dockPosition by remember(settings) { mutableStateOf(settings.dockPosition) }
    var orientation by remember(settings) { mutableStateOf(settings.orientation) }
    var iconSize by remember(settings) { mutableStateOf(settings.iconSize) }
    var bgStyle by remember(settings) { mutableStateOf(settings.backgroundStyle) }
    var bgOpacity by remember(settings) { mutableFloatStateOf(settings.backgroundOpacity) }
    var haptic by remember(settings) { mutableStateOf(settings.hapticFeedbackEnabled) }
    var dragEnabled by remember(settings) { mutableStateOf(settings.allowDragReposition) }
    var bootAutoStart by remember(settings) { mutableStateOf(settings.autoStartOnBoot) }
    var showLabels by remember(settings) { mutableStateOf(settings.showButtonLabels) }
    var debounceMs by remember(settings) { mutableFloatStateOf(settings.debounceDelayMs.toFloat()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Docking & Position", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Dock Position on Cover Screen:", fontSize = 13.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(6.dp))

                    DockPositionSelector(
                        selected = dockPosition,
                        onSelect = {
                            dockPosition = it
                            onSaveSettings(settings.copy(dockPosition = it))
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Orientation:", fontSize = 13.sp, color = TextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OverlayOrientation.values().forEach { opt ->
                            FilterChip(
                                selected = orientation == opt,
                                onClick = {
                                    orientation = opt
                                    onSaveSettings(settings.copy(orientation = opt))
                                },
                                label = { Text(opt.displayName) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Appearance & Sizing", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Icon Size:", fontSize = 13.sp, color = TextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconSize.values().forEach { size ->
                            FilterChip(
                                selected = iconSize == size,
                                onClick = {
                                    iconSize = size
                                    onSaveSettings(settings.copy(iconSize = size))
                                },
                                label = { Text(size.displayName) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Background Style:", fontSize = 13.sp, color = TextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BackgroundStyle.values().forEach { style ->
                            FilterChip(
                                selected = bgStyle == style,
                                onClick = {
                                    bgStyle = style
                                    onSaveSettings(settings.copy(backgroundStyle = style))
                                },
                                label = { Text(style.displayName) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Background Opacity: ${(bgOpacity * 100).toInt()}%", fontSize = 13.sp, color = TextSecondary)
                    Slider(
                        value = bgOpacity,
                        onValueChange = {
                            bgOpacity = it
                            onSaveSettings(settings.copy(backgroundOpacity = it))
                        },
                        valueRange = 0.2f..1.0f
                    )

                    HorizontalDivider(color = DarkCardBorder, modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show Button Labels", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Display label text below icon buttons", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(checked = showLabels, onCheckedChange = {
                            showLabels = it
                            onSaveSettings(settings.copy(showButtonLabels = it))
                        })
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Behavior & Gestures", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow Drag Repositioning", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Shows drag handle on overlay to move anywhere", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(checked = dragEnabled, onCheckedChange = {
                            dragEnabled = it
                            onSaveSettings(settings.copy(allowDragReposition = it))
                        })
                    }

                    HorizontalDivider(color = DarkCardBorder, modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Haptic Feedback", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Vibrate on button tap", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(checked = haptic, onCheckedChange = {
                            haptic = it
                            onSaveSettings(settings.copy(hapticFeedbackEnabled = it))
                        })
                    }

                    HorizontalDivider(color = DarkCardBorder, modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Start on Boot", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("Starts overlay service after device restart", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(checked = bootAutoStart, onCheckedChange = {
                            bootAutoStart = it
                            onSaveSettings(settings.copy(autoStartOnBoot = it))
                        })
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Tap Debounce Delay: ${debounceMs.toInt()} ms", fontSize = 13.sp, color = TextSecondary)
                    Slider(
                        value = debounceMs,
                        onValueChange = {
                            debounceMs = it
                            onSaveSettings(settings.copy(debounceDelayMs = it.toLong()))
                        },
                        valueRange = 300f..2000f
                    )
                }
            }
        }
    }
}

@Composable
fun DockPositionSelector(
    selected: DockPosition,
    onSelect: (DockPosition) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            DockButton("Top Left", DockPosition.TOP_LEFT, selected, Modifier.weight(1f), onSelect)
            DockButton("Top Center", DockPosition.TOP_CENTER, selected, Modifier.weight(1f), onSelect)
            DockButton("Top Right", DockPosition.TOP_RIGHT, selected, Modifier.weight(1f), onSelect)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            DockButton("Center Left", DockPosition.CENTER_LEFT, selected, Modifier.weight(1f), onSelect)
            DockButton("Custom (Drag)", DockPosition.CUSTOM, selected, Modifier.weight(1f), onSelect)
            DockButton("Center Right", DockPosition.CENTER_RIGHT, selected, Modifier.weight(1f), onSelect)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            DockButton("Bottom Left", DockPosition.BOTTOM_LEFT, selected, Modifier.weight(1f), onSelect)
            DockButton("Bottom Center", DockPosition.BOTTOM_CENTER, selected, Modifier.weight(1f), onSelect)
            DockButton("Bottom Right", DockPosition.BOTTOM_RIGHT, selected, Modifier.weight(1f), onSelect)
        }
    }
}

@Composable
fun DockButton(
    label: String,
    position: DockPosition,
    current: DockPosition,
    modifier: Modifier = Modifier,
    onSelect: (DockPosition) -> Unit
) {
    val isSelected = position == current
    Button(
        onClick = { onSelect(position) },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) PrimaryBlue else DarkSurfaceVariant,
            contentColor = if (isSelected) Color.White else TextSecondary
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(4.dp)
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun DisplaysTab(
    displays: List<DisplayInfo>,
    settings: OverlaySettings,
    onSelectDisplayMode: (TargetDisplayMode, Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Display Targeting Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))

                    TargetDisplayMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectDisplayMode(mode, settings.targetDisplayId) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.targetDisplayMode == mode,
                                onClick = { onSelectDisplayMode(mode, settings.targetDisplayId) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(mode.displayName, color = TextPrimary, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        item {
            Text("Hardware Displays Detected (${displays.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
        }

        items(displays) { info ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (info.isCoverScreen) Color(0xFF1E3A2F) else DarkSurface
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${info.name} (ID: ${info.displayId})",
                            fontWeight = FontWeight.Bold,
                            color = if (info.isCoverScreen) AccentGreen else TextPrimary
                        )

                        if (info.isCoverScreen) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AccentGreen)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Cover Screen", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Resolution: ${info.resolutionText}", fontSize = 12.sp, color = TextSecondary)
                    Text("Power State: ${info.stateText}", fontSize = 12.sp, color = TextSecondary)
                    Text("Detection: ${info.detectionReason}", fontSize = 11.sp, color = TextMuted)
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isGranted) AccentGreen else AccentAmber,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(title, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
            Text(description, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }

        if (!isGranted) {
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Grant", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ButtonEditDialog(
    initialButton: OverlayButtonConfig?,
    haConfig: HaConfig,
    onDismiss: () -> Unit,
    onSave: (OverlayButtonConfig) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var entityId by remember { mutableStateOf(initialButton?.entityId ?: "") }
    var domain by remember { mutableStateOf(initialButton?.domain ?: "light") }
    var service by remember { mutableStateOf(initialButton?.service ?: "toggle") }
    var iconName by remember { mutableStateOf(initialButton?.iconName ?: "lightbulb") }
    var label by remember { mutableStateOf(initialButton?.label ?: "") }
    var customColorHex by remember { mutableStateOf(initialButton?.customColorHex ?: "#F59E0B") }
    var serviceDataJson by remember { mutableStateOf(initialButton?.serviceDataJson ?: "") }
    var guardSensorEntityId by remember { mutableStateOf(initialButton?.guardSensorEntityId ?: "") }
    var guardTriggerState by remember { mutableStateOf(initialButton?.guardTriggerState ?: "on") }
    var requireConfirmationWhenLocked by remember { mutableStateOf(initialButton?.requireConfirmationWhenLocked ?: false) }

    var haEntities by remember { mutableStateOf<List<HaEntityState>>(emptyList()) }
    var isLoadingEntities by remember { mutableStateOf(false) }
    var showEntityPickerSheet by remember { mutableStateOf(false) }

    val iconOptions = listOf(
        "lightbulb", "power", "lock", "lock_open", "door", "blinds",
        "thermostat", "fan", "sparkles", "touch", "code", "bolt",
        "music", "garage", "camera", "vacuum", "shield", "refresh"
    )

    val colorPresets = listOf(
        "#F59E0B", "#3B82F6", "#10B981", "#EF4444", "#8B5CF6", "#06B6D4", "#EC4899", "#FFFFFF"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth()
            ) {
                Text(
                    text = if (initialButton == null) "Add Quick Action Button" else "Edit Action Button",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = entityId,
                            onValueChange = {
                                entityId = it
                                val d = OverlayButtonConfig.extractDomain(it)
                                domain = d
                                service = OverlayButtonConfig.defaultServiceForDomain(d)
                                iconName = OverlayButtonConfig.defaultIconForDomain(d)
                            },
                            label = { Text("Entity ID (e.g. light.kitchen)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (haConfig.isValid) {
                                    IconButton(onClick = {
                                        isLoadingEntities = true
                                        showEntityPickerSheet = true
                                        coroutineScope.launch {
                                            val client = HomeAssistantClient(haConfig)
                                            val res = client.fetchEntities()
                                            if (res is HaResult.Success) {
                                                haEntities = res.data
                                            }
                                            isLoadingEntities = false
                                        }
                                    }) {
                                        Icon(Icons.Default.Search, contentDescription = "Pick Entity", tint = PrimaryBlue)
                                    }
                                }
                            }
                        )
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = domain,
                                onValueChange = { domain = it },
                                label = { Text("Domain") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = service,
                                onValueChange = { service = it },
                                label = { Text("Service") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text("Button Label (e.g. Kitchen)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Text("Select Icon:", fontSize = 13.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            iconOptions.forEach { opt ->
                                val isSelected = iconName == opt
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) PrimaryBlue else DarkSurfaceVariant)
                                        .clickable { iconName = opt },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = resolveDrawableResource(opt)),
                                        contentDescription = opt,
                                        tint = if (isSelected) Color.White else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text("Accent Color:", fontSize = 13.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            colorPresets.forEach { hex ->
                                val isSelected = customColorHex.equals(hex, ignoreCase = true)
                                val col = parseColorSafely(hex) ?: Color.White
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(col)
                                        .border(
                                            width = if (isSelected) 2.dp else 0.dp,
                                            color = if (isSelected) Color.White else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { customColorHex = hex }
                                )
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = serviceDataJson,
                            onValueChange = { serviceDataJson = it },
                            label = { Text("Optional Service Data JSON") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        HorizontalDivider(color = DarkCardBorder, modifier = Modifier.padding(vertical = 4.dp))
                        Text("Safety & Confirmation Guards", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    }

                    item {
                        OutlinedTextField(
                            value = guardSensorEntityId,
                            onValueChange = { guardSensorEntityId = it },
                            label = { Text("Guard Binary Sensor Entity (Optional)") },
                            placeholder = { Text("e.g. binary_sensor.hallway_light") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("If this sensor is ON, tapping requires 10s confirmation + warning vibration.", fontSize = 11.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp))
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Require Confirmation When Locked", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Prevents accidental unlatch/unlock if door is locked", fontSize = 11.sp, color = TextSecondary)
                            }
                            Switch(
                                checked = requireConfirmationWhenLocked,
                                onCheckedChange = { requireConfirmationWhenLocked = it }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val btn = (initialButton ?: OverlayButtonConfig()).copy(
                                entityId = entityId.trim(),
                                domain = domain.trim(),
                                service = service.trim(),
                                iconName = iconName,
                                label = label.trim(),
                                customColorHex = customColorHex,
                                serviceDataJson = if (serviceDataJson.isBlank()) null else serviceDataJson.trim(),
                                guardSensorEntityId = if (guardSensorEntityId.isBlank()) null else guardSensorEntityId.trim(),
                                guardTriggerState = guardTriggerState.trim(),
                                requireConfirmationWhenLocked = requireConfirmationWhenLocked,
                                targetLockEntityId = if (requireConfirmationWhenLocked) entityId.trim() else null
                            )
                            onSave(btn)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        enabled = entityId.isNotBlank() && service.isNotBlank()
                    ) {
                        Text("Save Button")
                    }
                }
            }
        }
    }

    if (showEntityPickerSheet) {
        Dialog(onDismissRequest = { showEntityPickerSheet = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface,
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Select Home Assistant Entity", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoadingEntities) {
                        Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(haEntities) { ent ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            entityId = ent.entityId
                                            val d = OverlayButtonConfig.extractDomain(ent.entityId)
                                            domain = d
                                            service = OverlayButtonConfig.defaultServiceForDomain(d)
                                            iconName = OverlayButtonConfig.defaultIconForDomain(d)
                                            label = ent.friendlyName
                                            showEntityPickerSheet = false
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(ent.friendlyName, fontWeight = FontWeight.Medium, color = TextPrimary)
                                        Text("${ent.entityId} [${ent.state}]", fontSize = 11.sp, color = TextSecondary)
                                    }
                                }
                                HorizontalDivider(color = DarkCardBorder)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun resolveDrawableResource(name: String): Int {
    return when (name.lowercase()) {
        "lightbulb" -> R.drawable.ic_lightbulb
        "power" -> R.drawable.ic_power
        "lock" -> R.drawable.ic_lock
        "lock_open" -> R.drawable.ic_lock_open
        "door" -> R.drawable.ic_door
        "blinds" -> R.drawable.ic_blinds
        "thermostat" -> R.drawable.ic_thermostat
        "fan" -> R.drawable.ic_fan
        "sparkles" -> R.drawable.ic_sparkles
        "touch" -> R.drawable.ic_touch
        "code" -> R.drawable.ic_code
        "bolt" -> R.drawable.ic_bolt
        "music" -> R.drawable.ic_music
        "garage" -> R.drawable.ic_garage
        "camera" -> R.drawable.ic_camera
        "vacuum" -> R.drawable.ic_vacuum
        "shield" -> R.drawable.ic_shield
        "refresh" -> R.drawable.ic_refresh
        else -> R.drawable.ic_power
    }
}

fun parseColorSafely(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        null
    }
}
