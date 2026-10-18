package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    onClearHistory: () -> Unit,
    onClearFavorites: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearFavoritesDialog by remember { mutableStateOf(false) }
    var resumeLastPlayback by remember { mutableStateOf(true) }
    var autoPauseOnHeadsetUnplug by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // Appearance
        item {
            SectionHeader(title = "Appearance")
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.Palette,
                    title = "App Theme",
                    subtitle = when (currentTheme) {
                        "light" -> "Light"
                        "dark" -> "Dark"
                        else -> "System Default"
                    },
                    onClick = { showThemeDialog = true },
                    tag = "theme_setting_item"
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Playback Behavior
        item {
            SectionHeader(title = "Playback")
            SettingsCard {
                SettingsSwitchItem(
                    icon = Icons.Default.PlayCircleOutline,
                    title = "Resume Last Playback",
                    subtitle = "Remember last queue and track position on restart",
                    checked = resumeLastPlayback,
                    onCheckedChange = { resumeLastPlayback = it }
                )
                SettingsSwitchItem(
                    icon = Icons.Default.Headphones,
                    title = "Pause on Headset Unplug",
                    subtitle = "Automatically pause when headphones or Bluetooth disconnect",
                    checked = autoPauseOnHeadsetUnplug,
                    onCheckedChange = { autoPauseOnHeadsetUnplug = it }
                )
                SettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = "Audio Focus Handling",
                    subtitle = "Automatic pause and ducking on calls and notifications",
                    onClick = { },
                    tag = "audio_focus_item"
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Library & Data Management
        item {
            SectionHeader(title = "Data Management")
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.Delete,
                    title = "Clear Playback History",
                    subtitle = "Remove all entries from recently played tracks",
                    onClick = { showClearHistoryDialog = true },
                    tag = "clear_history_setting"
                )
                SettingsItem(
                    icon = Icons.Default.Delete,
                    title = "Clear Favorites",
                    subtitle = "Remove all tracks from your favorites collection",
                    onClick = { showClearFavoritesDialog = true },
                    tag = "clear_fav_setting"
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Privacy & Security
        item {
            SectionHeader(title = "Privacy & Security")
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "100% Offline & Private",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• No internet connection required\n• Zero tracking or analytics\n• Audio files never leave your device\n• No account or registration needed\n• No advertisements or bloatware",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // About
        item {
            SectionHeader(title = "About")
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Pulse Music",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Version 1.0 (Media3 ExoPlayer)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "A lightweight, reliable, high-performance offline audio player crafted with Jetpack Compose, Media3, and Material 3 design.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Theme Selection Dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Choose Theme") },
            text = {
                Column {
                    ThemeOption("System Default", "system", currentTheme) {
                        onThemeChange("system")
                        showThemeDialog = false
                    }
                    ThemeOption("Light", "light", currentTheme) {
                        onThemeChange("light")
                        showThemeDialog = false
                    }
                    ThemeOption("Dark", "dark", currentTheme) {
                        onThemeChange("dark")
                        showThemeDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Clear History Dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear History?") },
            text = { Text("This will permanently remove all tracks from your playback history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        showClearHistoryDialog = false
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear Favorites Dialog
    if (showClearFavoritesDialog) {
        AlertDialog(
            onDismissRequest = { showClearFavoritesDialog = false },
            title = { Text("Clear Favorites?") },
            text = { Text("This will remove all songs from your favorites list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearFavorites()
                        showClearFavoritesDialog = false
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearFavoritesDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag),
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ThemeOption(
    label: String,
    value: String,
    currentValue: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = value == currentValue,
            onClick = onSelect
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
