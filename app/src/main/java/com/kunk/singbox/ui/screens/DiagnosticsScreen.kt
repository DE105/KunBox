package com.kunk.singbox.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.viewmodel.DiagnosticsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    navController: NavController,
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val showResultDialog by viewModel.showResultDialog.collectAsState()
    val resultTitle by viewModel.resultTitle.collectAsState()
    val resultMessage by viewModel.resultMessage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    if (showResultDialog) {
        ConfirmDialog(
            title = resultTitle,
            message = resultMessage,
            confirmText = "确定",
            onConfirm = { viewModel.dismissDialog() },
            onDismiss = { viewModel.dismissDialog() }
        )
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("网络诊断", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PureWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            StandardCard {
                SettingItem(
                    title = "连通性检查",
                    subtitle = if (isLoading) "正在检查..." else "检查 Google 连接状态",
                    icon = Icons.Rounded.NetworkCheck,
                    onClick = { viewModel.runConnectivityCheck() },
                    enabled = !isLoading
                )
                SettingItem(
                    title = "Ping 测试",
                    subtitle = if (isLoading) "正在测试..." else "ICMP Ping 目标主机",
                    icon = Icons.Rounded.Speed,
                    onClick = { viewModel.runPingTest() },
                    enabled = !isLoading
                )
                SettingItem(
                    title = "DNS 查询",
                    subtitle = if (isLoading) "正在查询..." else "解析域名 IP",
                    icon = Icons.Rounded.Dns,
                    onClick = { viewModel.runDnsQuery() },
                    enabled = !isLoading
                )
                SettingItem(
                    title = "路由测试",
                    subtitle = if (isLoading) "正在测试..." else "检查分流规则匹配",
                    icon = Icons.Rounded.Route,
                    onClick = { viewModel.runRoutingTest() },
                    enabled = !isLoading
                )
            }
        }
    }
}