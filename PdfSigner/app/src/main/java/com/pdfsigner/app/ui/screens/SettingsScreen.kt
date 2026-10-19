package com.pdfsigner.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfsigner.app.BuildConfig
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.prefs.AppLanguage
import com.pdfsigner.app.domain.prefs.AppTheme
import com.pdfsigner.app.ui.theme.Brand700
import com.pdfsigner.app.ui.theme.Brand900
import com.pdfsigner.app.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ?? Header ????????????????????????????????????????????????????????????
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Brand900, Brand700)))
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Settings, null,
                        tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = Color.White)
                    Text(stringResource(R.string.settings_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f))
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // ?? Appearance ???????????????????????????????????????????????????
            SettingsSection(label = stringResource(R.string.settings_section_appearance))

            // Theme
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsRowLabel(
                        icon  = Icons.Filled.DarkMode,
                        label = stringResource(R.string.settings_theme)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppTheme.entries.forEach { t ->
                            val selected = state.theme == t
                            val label = when (t) {
                                AppTheme.SYSTEM -> stringResource(R.string.theme_system)
                                AppTheme.LIGHT  -> stringResource(R.string.theme_light)
                                AppTheme.DARK   -> stringResource(R.string.theme_dark)
                            }
                            FilterChip(
                                selected = selected,
                                onClick  = { viewModel.setTheme(t) },
                                label    = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Language
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsRowLabel(
                        icon  = Icons.Filled.Language,
                        label = stringResource(R.string.settings_language)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppLanguage.entries.forEach { l ->
                            val selected = state.language == l
                            FilterChip(
                                selected = selected,
                                onClick  = {
                                    viewModel.setLanguage(l)
                                    // Restart to apply locale
                                    val restart = context.packageManager
                                        .getLaunchIntentForPackage(context.packageName)
                                        ?.apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                                     Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                        }
                                    if (restart != null) context.startActivity(restart)
                                },
                                label    = { Text(l.displayName, style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // ?? Accessibility ????????????????????????????????????????????????
            Spacer(Modifier.height(8.dp))
            SettingsSection(label = stringResource(R.string.settings_section_accessibility))

            // Font size
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsRowLabel(
                        icon  = Icons.Filled.FormatSize,
                        label = stringResource(R.string.settings_font_size)
                    )
                    val labels = listOf(
                        stringResource(R.string.font_default),
                        stringResource(R.string.font_large),
                        stringResource(R.string.font_xl)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        labels.forEachIndexed { idx, lbl ->
                            FilterChip(
                                selected = state.fontScale == idx,
                                onClick  = { viewModel.setFontScale(idx) },
                                label    = { Text(lbl, style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // High contrast
            SettingsCard {
                SettingsSwitchRow(
                    icon     = Icons.Filled.Contrast,
                    label    = stringResource(R.string.settings_high_contrast),
                    subtitle = stringResource(R.string.settings_high_contrast_desc),
                    checked  = state.highContrast,
                    onChange = { viewModel.setHighContrast(it) }
                )
            }

            // Haptics
            SettingsCard {
                SettingsSwitchRow(
                    icon     = Icons.Filled.Vibration,
                    label    = stringResource(R.string.settings_haptics),
                    subtitle = stringResource(R.string.settings_haptics_desc),
                    checked  = state.hapticsEnabled,
                    onChange = { viewModel.setHaptics(it) }
                )
            }

            // ?? About ????????????????????????????????????????????????????????
            Spacer(Modifier.height(8.dp))
            SettingsSection(label = stringResource(R.string.settings_section_about))

            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsInfoRow(
                        icon  = Icons.Filled.Info,
                        label = stringResource(R.string.settings_version),
                        value = BuildConfig.VERSION_NAME
                    )
                    HorizontalDivider()
                    SettingsInfoRow(
                        icon  = Icons.Filled.Code,
                        label = stringResource(R.string.settings_build),
                        value = BuildConfig.VERSION_CODE.toString()
                    )
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com"))
                                runCatching { context.startActivity(intent) }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Filled.Policy, null,
                                tint     = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.settings_privacy),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(Icons.Filled.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Reusable setting components
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun SettingsSection(label: String) {
    Text(
        text     = label.uppercase(),
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape  = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content  = content
        )
    }
}

@Composable
private fun SettingsRowLabel(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null,
            tint     = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp))
        Text(label,
            style      = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier              = Modifier.weight(1f)
        ) {
            Icon(icon, null,
                tint     = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Column {
                Text(label,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsInfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null,
                tint     = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Text(label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
        }
        Text(value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
