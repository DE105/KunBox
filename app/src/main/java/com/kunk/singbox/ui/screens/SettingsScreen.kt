package com.kunk.singbox.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.AppThemeMode
import com.kunk.singbox.model.ImportOptions
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.ui.components.AboutDialog
import com.kunk.singbox.ui.components.EditableTextItem
import com.kunk.singbox.ui.components.ExportProgressDialog
import com.kunk.singbox.ui.components.ImportPreviewDialog
import com.kunk.singbox.ui.components.ImportProgressDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.SettingSwitchItem
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.ValidatingDialog
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.viewmodel.ExportState
import com.kunk.singbox.viewmodel.ImportState
import com.kunk.singbox.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val settings by viewModel.settings.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val importState by viewModel.importState.collectAsState()
    
    var showAboutDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var isUpdatingRuleSets by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    
    // 文件选择器 - 导出
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportData(it) }
    }
    
    // 文件选择器 - 导入
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.validateImportFile(it) }
    }
    
    // 生成导出文件名
    fun generateExportFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "singbox_backup_${dateFormat.format(Date())}.json"
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false }
        )
    }

    if (showThemeDialog) {
        SingleSelectDialog(
            title = "应用主题",
            options = AppThemeMode.entries.map { it.displayName },
            selectedIndex = AppThemeMode.entries.indexOf(settings.appTheme),
            onSelect = { index ->
                viewModel.setAppTheme(AppThemeMode.entries[index])
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }
    
    // 导出状态对话框
    ExportProgressDialog(
        state = exportState,
        onDismiss = { viewModel.resetExportState() }
    )
    
    // 导入预览对话框
    if (importState is ImportState.Preview) {
        val previewState = importState as ImportState.Preview
        ImportPreviewDialog(
            summary = previewState.summary,
            onConfirm = {
                viewModel.confirmImport(previewState.uri, ImportOptions(overwriteExisting = true))
            },
            onDismiss = { viewModel.resetImportState() }
        )
    }
    
    // 导入进度/结果对话框
    ImportProgressDialog(
        state = importState,
        onDismiss = { viewModel.resetImportState() }
    )
    
    // 验证中对话框
    if (importState is ImportState.Validating) {
        ValidatingDialog()
    }
    
    // 导入错误处理（如果在 Preview 之前就出错）
    LaunchedEffect(importState) {
        if (importState is ImportState.Error) {
            // 错误会在 ImportProgressDialog 中显示
        }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = statusBarPadding.calculateTopPadding())
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 1. Connection & Startup
        SettingsGroupTitle("通用")
        StandardCard {
            SettingItem(
                title = "应用主题",
                value = settings.appTheme.displayName,
                icon = Icons.Rounded.Brightness6,
                onClick = { showThemeDialog = true }
            )
            SettingItem(
                title = "连接与启动",
                subtitle = "自动连接、断线重连",
                icon = Icons.Rounded.PowerSettingsNew,
                onClick = { navController.navigate(Screen.ConnectionSettings.route) }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // 2. Network
        SettingsGroupTitle("网络")
        StandardCard {
            SettingItem(
                title = "路由设置",
                subtitle = "模式、规则集、默认规则",
                icon = Icons.Rounded.Route,
                onClick = { navController.navigate(Screen.RoutingSettings.route) }
            )
            SettingItem(
                title = "DNS 设置",
                value = "自动",
                icon = Icons.Rounded.Dns,
                onClick = { navController.navigate(Screen.DnsSettings.route) }
            )
            SettingItem(
                title = "TUN / VPN",
                subtitle = "堆栈、MTU、分应用代理",
                icon = Icons.Rounded.VpnKey,
                onClick = { navController.navigate(Screen.TunSettings.route) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Tools
        SettingsGroupTitle("工具")
        StandardCard {
            SettingItem(
                title = if (isUpdatingRuleSets) updateMessage else "更新规则集",
                subtitle = if (isUpdatingRuleSets) "正在下载..." else "手动更新广告与路由规则",
                icon = Icons.Rounded.Sync,
                trailing = {
                    if (isUpdatingRuleSets) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    }
                },
                onClick = {
                    if (!isUpdatingRuleSets) {
                        isUpdatingRuleSets = true
                        updateMessage = "准备更新..."
                        scope.launch {
                            try {
                                val success = RuleSetRepository.getInstance(context).ensureRuleSetsReady(
                                    forceUpdate = true,
                                    allowNetwork = true
                                ) {
                                    updateMessage = it
                                }
                                updateMessage = if (success) "更新成功" else "更新失败"
                                Toast.makeText(
                                    context,
                                    if (success) "规则集更新成功" else "规则集更新失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                updateMessage = "发生错误: ${e.message}"
                                Toast.makeText(
                                    context,
                                    "规则集更新失败: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                kotlinx.coroutines.delay(1000)
                                isUpdatingRuleSets = false
                            }
                        }
                    }
                }
            )
            SettingSwitchItem(
                title = "规则集定时更新",
                subtitle = if (settings.ruleSetAutoUpdateEnabled)
                    "每 ${settings.ruleSetAutoUpdateInterval} 分钟自动更新"
                else
                    "开启后自动更新所有远程规则集",
                icon = Icons.Rounded.Schedule,
                checked = settings.ruleSetAutoUpdateEnabled,
                onCheckedChange = { viewModel.setRuleSetAutoUpdateEnabled(it) }
            )
            if (settings.ruleSetAutoUpdateEnabled) {
                EditableTextItem(
                    title = "更新间隔",
                    value = "${settings.ruleSetAutoUpdateInterval} 分钟",
                    onValueChange = { newValue ->
                        val interval = newValue.replace(" 分钟", "").toIntOrNull()
                        if (interval != null && interval >= 15) {
                            viewModel.setRuleSetAutoUpdateInterval(interval)
                        } else {
                            Toast.makeText(context, "更新间隔至少为 15 分钟", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            SettingSwitchItem(
                title = "调试模式",
                subtitle = "开启后记录详细日志（需重启服务）",
                icon = Icons.Rounded.BugReport,
                checked = settings.debugLoggingEnabled,
                onCheckedChange = { viewModel.setDebugLoggingEnabled(it) }
            )
            SettingItem(
                title = "运行日志",
                icon = Icons.Rounded.History,
                onClick = { navController.navigate(Screen.Logs.route) }
            )
            SettingItem(
                title = "网络诊断",
                icon = Icons.Rounded.BugReport,
                onClick = { navController.navigate(Screen.Diagnostics.route) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // 4. 数据管理
        SettingsGroupTitle("数据管理")
        StandardCard {
            SettingItem(
                title = "导出数据",
                subtitle = "备份所有配置和设置到文件",
                icon = Icons.Rounded.Upload,
                onClick = {
                    exportLauncher.launch(generateExportFileName())
                }
            )
            SettingItem(
                title = "导入数据",
                subtitle = "从备份文件恢复配置和设置",
                icon = Icons.Rounded.Download,
                onClick = {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. About
        SettingsGroupTitle("关于")
        StandardCard {
            SettingItem(
                title = "关于应用",
                icon = Icons.Rounded.Info,
                onClick = { showAboutDialog = true }
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}