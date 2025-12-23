package com.kunk.singbox.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.ProfileNodeSelectDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.ui.navigation.Screen
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral800
import com.kunk.singbox.ui.theme.Neutral700
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.NodeUi
import kotlinx.coroutines.launch

data class DefaultRuleSetConfig(
    val tag: String,
    val description: String,
    val url: String,
    val outboundMode: RuleSetOutboundMode,
    val format: String = "binary"
)

val CHINA_DEFAULT_RULE_SETS = listOf(
    DefaultRuleSetConfig(
        tag = "geosite-cn",
        description = "中国大陆域名直连",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs",
        outboundMode = RuleSetOutboundMode.DIRECT
    ),
    DefaultRuleSetConfig(
        tag = "geoip-cn",
        description = "中国大陆IP直连",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
        outboundMode = RuleSetOutboundMode.DIRECT
    ),
    DefaultRuleSetConfig(
        tag = "geosite-geolocation-!cn",
        description = "非中国域名走代理",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-geolocation-!cn.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-category-ads-all",
        description = "广告域名拦截",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs",
        outboundMode = RuleSetOutboundMode.BLOCK
    ),
    DefaultRuleSetConfig(
        tag = "geosite-private",
        description = "私有网络直连",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-private.srs",
        outboundMode = RuleSetOutboundMode.DIRECT
    ),
    DefaultRuleSetConfig(
        tag = "geosite-apple",
        description = "苹果服务走代理",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-apple.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-youtube",
        description = "YouTube走代理",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-youtube.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-google",
        description = "Google走代理",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-telegram",
        description = "Telegram走代理",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-telegram.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-facebook",
        description = "Facebook走代理",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-facebook.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-openai",
        description = "OpenAI/ChatGPT走代理",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-openai.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    ),
    DefaultRuleSetConfig(
        tag = "geosite-github",
        description = "GitHub走代理",
        url = "https://ghp.ci/https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-github.srs",
        outboundMode = RuleSetOutboundMode.PROXY
    )
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RuleSetsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    val downloadingRuleSets by settingsViewModel.downloadingRuleSets.collectAsState()
    val allNodes by nodesViewModel.allNodes.collectAsState()
    val nodesForSelection by nodesViewModel.filteredAllNodes.collectAsState()
    val groups by nodesViewModel.allNodeGroups.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()

    DisposableEffect(Unit) {
        nodesViewModel.setAllNodesUiActive(true)
        onDispose {
            nodesViewModel.setAllNodesUiActive(false)
        }
    }
    
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRuleSet by remember { mutableStateOf<RuleSet?>(null) }
    var showDefaultRuleSetsDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateMapOf<String, Boolean>() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    
    // Outbound/Inbound dialog states
    var outboundEditingRuleSet by remember { mutableStateOf<RuleSet?>(null) }
    var showOutboundModeDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showNodeSelectionDialog by remember { mutableStateOf(false) }
    var showInboundDialog by remember { mutableStateOf(false) }
    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val availableInbounds = listOf("tun", "mixed")

    val selectionNodes = nodesForSelection
    
    // Helper functions for node resolution
    fun resolveNodeByStoredValue(value: String?): NodeUi? {
        if (value.isNullOrBlank()) return null
        val parts = value.split("::", limit = 2)
        if (parts.size == 2) {
            val profileId = parts[0]
            val name = parts[1]
            return allNodes.find { it.sourceProfileId == profileId && it.name == name }
        }
        return allNodes.find { it.id == value } ?: allNodes.find { it.name == value }
    }
    
    fun toNodeRef(node: NodeUi): String = "${node.sourceProfileId}::${node.name}"

    // Reordering State
    val ruleSets = remember { mutableStateListOf<RuleSet>() }
    // Only sync if dragging is NOT active to avoid conflicts
    val isDragging = remember { mutableStateOf(false) }
    var suppressPlacementAnimation by remember { mutableStateOf(false) }
    val enablePlacementAnimation = false
    
    LaunchedEffect(settings.ruleSets) {
        if (!isDragging.value) {
            // Only update if the set of IDs has changed or size changed
            // This prevents overwriting local reordering with stale remote data immediately after drop
            val currentIds = ruleSets.map { it.id }.toSet()
            val newIds = settings.ruleSets.map { it.id }.toSet()
            
            if (currentIds != newIds || ruleSets.size != settings.ruleSets.size || ruleSets.isEmpty()) {
                ruleSets.clear()
                ruleSets.addAll(settings.ruleSets)
            } else {
                // If IDs match but order differs, we assume local state is correct (unless we want to force sync)
                // To be safe, if the lists are drastically different (e.g. initial load), we sync.
                // But for reordering, we trust the local operation.
                // Double check if we need to sync for property updates (e.g. name change)
                if (ruleSets.map { it.toString() } != settings.ruleSets.map { it.toString() }) {
                    // Content might have changed, but try to preserve order if possible?
                    // For now, simpler approach: if local state matches the ID set, we trust local order.
                    // But if properties changed, we should update items in place?
                    // Let's just do a smart update:
                    settings.ruleSets.forEach { newRule ->
                        val index = ruleSets.indexOfFirst { it.id == newRule.id }
                        if (index != -1 && ruleSets[index] != newRule) {
                            ruleSets[index] = newRule
                        }
                    }
                }
            }
        }
    }

    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggingItemOffset by remember { mutableStateOf(0f) }
    var itemHeightPx by remember { mutableStateOf(0f) }
    
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
    }
    
    fun toggleSelection(id: String) {
        selectedItems[id] = !(selectedItems[id] ?: false)
        if (selectedItems.none { it.value }) {
            exitSelectionMode()
        }
    }

    if (showAddDialog) {
        RuleSetEditorDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { ruleSet ->
                settingsViewModel.addRuleSet(ruleSet)
                showAddDialog = false
            }
        )
    }

    if (editingRuleSet != null) {
        RuleSetEditorDialog(
            initialRuleSet = editingRuleSet,
            onDismiss = { editingRuleSet = null },
            onConfirm = { ruleSet ->
                settingsViewModel.updateRuleSet(ruleSet)
                editingRuleSet = null
            },
            onDelete = {
                settingsViewModel.deleteRuleSet(editingRuleSet!!.id)
                editingRuleSet = null
            }
        )
    }
    
    if (showDefaultRuleSetsDialog) {
        DefaultRuleSetsDialog(
            existingTags = settings.ruleSets.map { it.tag },
            onDismiss = { showDefaultRuleSetsDialog = false },
            onAdd = { configs ->
                val ruleSets = configs.map { config ->
                    RuleSet(
                        tag = config.tag,
                        type = RuleSetType.REMOTE,
                        format = config.format,
                        url = config.url,
                        outboundMode = config.outboundMode
                    )
                }
                settingsViewModel.addRuleSets(ruleSets) { addedCount ->
                    scope.launch {
                        snackbarHostState.showSnackbar("已添加 $addedCount 个规则集")
                    }
                }
                showDefaultRuleSetsDialog = false
            }
        )
    }
    
    if (showDeleteConfirmDialog) {
        val selectedCount = selectedItems.count { it.value }
        ConfirmDialog(
            title = "删除规则集",
            message = "确定要删除选中的 $selectedCount 个规则集吗？",
            confirmText = "删除",
            onConfirm = {
                val idsToDelete = selectedItems.filter { it.value }.keys.toList()
                settingsViewModel.deleteRuleSets(idsToDelete)
                scope.launch {
                    snackbarHostState.showSnackbar("已删除 $selectedCount 个规则集")
                }
                showDeleteConfirmDialog = false
                exitSelectionMode()
            },
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }
    
    // Outbound Mode Dialog
    if (showOutboundModeDialog && outboundEditingRuleSet != null) {
        val options = RuleSetOutboundMode.entries.map { it.displayName }
        val currentMode = outboundEditingRuleSet!!.outboundMode ?: RuleSetOutboundMode.DIRECT
        SingleSelectDialog(
            title = "选择出站模式",
            options = options,
            selectedIndex = RuleSetOutboundMode.entries.indexOf(currentMode),
            onSelect = { index ->
                val selectedMode = RuleSetOutboundMode.entries[index]
                val updatedRuleSet = outboundEditingRuleSet!!.copy(outboundMode = selectedMode, outboundValue = null)

                if (selectedMode == RuleSetOutboundMode.NODE ||
                    selectedMode == RuleSetOutboundMode.PROFILE ||
                    selectedMode == RuleSetOutboundMode.GROUP
                ) {
                    outboundEditingRuleSet = updatedRuleSet
                    showOutboundModeDialog = false

                    when (selectedMode) {
                        RuleSetOutboundMode.NODE -> {
                            showNodeSelectionDialog = true
                        }
                        RuleSetOutboundMode.PROFILE -> {
                            targetSelectionTitle = "选择配置"
                            targetOptions = profiles.map { it.name to it.id }
                        }
                        RuleSetOutboundMode.GROUP -> {
                            targetSelectionTitle = "选择节点组"
                            targetOptions = groups.map { it to it }
                        }
                        else -> {}
                    }
                    if (selectedMode != RuleSetOutboundMode.NODE) {
                        showTargetSelectionDialog = true
                    }
                } else {
                    settingsViewModel.updateRuleSet(updatedRuleSet)
                    outboundEditingRuleSet = null
                    showOutboundModeDialog = false
                }
            },
            onDismiss = {
                showOutboundModeDialog = false
                outboundEditingRuleSet = null
            }
        )
    }

    // Target Selection Dialog
    if (showTargetSelectionDialog && outboundEditingRuleSet != null) {
        val currentValue = outboundEditingRuleSet!!.outboundValue
        val currentRef = resolveNodeByStoredValue(currentValue)?.let { toNodeRef(it) } ?: currentValue
        SingleSelectDialog(
            title = targetSelectionTitle,
            options = targetOptions.map { it.first },
            selectedIndex = targetOptions.indexOfFirst { it.second == currentRef },
            onSelect = { index ->
                val selectedValue = targetOptions[index].second
                val updatedRuleSet = outboundEditingRuleSet!!.copy(outboundValue = selectedValue)
                settingsViewModel.updateRuleSet(updatedRuleSet)
                showTargetSelectionDialog = false
                outboundEditingRuleSet = null
            },
            onDismiss = {
                showTargetSelectionDialog = false
                outboundEditingRuleSet = null
            }
        )
    }

    if (showNodeSelectionDialog && outboundEditingRuleSet != null) {
        val currentValue = outboundEditingRuleSet!!.outboundValue
        val currentRef = resolveNodeByStoredValue(currentValue)?.let { toNodeRef(it) } ?: currentValue
        ProfileNodeSelectDialog(
            title = "选择节点",
            profiles = profiles,
            nodesForSelection = selectionNodes,
            selectedNodeRef = currentRef,
            onSelect = { ref ->
                val updatedRuleSet = outboundEditingRuleSet!!.copy(outboundValue = ref)
                settingsViewModel.updateRuleSet(updatedRuleSet)
            },
            onDismiss = {
                showNodeSelectionDialog = false
                outboundEditingRuleSet = null
            }
        )
    }

    // Inbound Dialog
    if (showInboundDialog && outboundEditingRuleSet != null) {
        AlertDialog(
            onDismissRequest = {
                showInboundDialog = false
                outboundEditingRuleSet = null
            },
            containerColor = Neutral800,
            shape = RoundedCornerShape(24.dp),
            title = { Text("选择入站", color = TextPrimary) },
            text = {
                Column {
                    availableInbounds.forEach { inbound ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val currentInbounds = (outboundEditingRuleSet!!.inbounds ?: emptyList()).toMutableList()
                                    if (currentInbounds.contains(inbound)) {
                                        currentInbounds.remove(inbound)
                                    } else {
                                        currentInbounds.add(inbound)
                                    }
                                    outboundEditingRuleSet = outboundEditingRuleSet!!.copy(inbounds = currentInbounds)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = (outboundEditingRuleSet!!.inbounds ?: emptyList()).contains(inbound),
                                onCheckedChange = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = inbound, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.updateRuleSet(outboundEditingRuleSet!!)
                        showInboundDialog = false
                        outboundEditingRuleSet = null
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showInboundDialog = false
                        outboundEditingRuleSet = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        val selectedCount = selectedItems.count { it.value }
                        Text("已选择 $selectedCount 项", color = TextPrimary)
                    } else {
                        Text("规则集管理", color = TextPrimary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            exitSelectionMode()
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            if (isSelectionMode) Icons.Rounded.Close else Icons.Rounded.ArrowBack,
                            contentDescription = if (isSelectionMode) "取消" else "返回",
                            tint = PureWhite
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        val selectedCount = selectedItems.count { it.value }
                        IconButton(
                            onClick = { showDeleteConfirmDialog = true },
                            enabled = selectedCount > 0
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "删除",
                                tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else TextSecondary
                            )
                        }
                    } else {
                        IconButton(onClick = { navController.navigate(Screen.RuleSetHub.route) }) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = "导入", tint = PureWhite)
                        }
                        Box {
                            var showAddMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showAddMenu = true }) {
                                Icon(Icons.Rounded.Add, contentDescription = "添加", tint = PureWhite)
                            }
                            DropdownMenu(
                                expanded = showAddMenu,
                                onDismissRequest = { showAddMenu = false },
                                modifier = Modifier.background(Neutral700)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("添加规则集", color = TextPrimary) },
                                    onClick = {
                                        showAddMenu = false
                                        showAddDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("默认规则", color = TextPrimary) },
                                    onClick = {
                                        showAddMenu = false
                                        showDefaultRuleSetsDialog = true
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (ruleSets.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无规则集",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                items(ruleSets.size, key = { ruleSets[it].id }) { index ->
                    val ruleSet = ruleSets[index]
                    
                    // Calculate visual offset based on drag state
                    // We DO NOT swap the list during drag to avoid recomposition issues.
                    // Instead, we visually shift items.
                    
                    val currentDraggingIndex = draggingItemIndex
                    val currentDragOffset = draggingItemOffset
                    
                    // Calculate the "projected" index of the dragged item
                    val itemHeightWithSpacing = itemHeightPx + 16.dp.value * 2.625f // Approx px conversion, better to use density
                    // Wait, we can get density. But simple calculation:
                    // If we know itemHeightPx, and we assume spacing is consistent.
                    
                    var translationY = 0f
                    var zIndex = 0f
                    
                    if (currentDraggingIndex != null && itemHeightPx > 0) {
                        if (index == currentDraggingIndex) {
                            translationY = currentDragOffset
                            zIndex = 1f
                        } else {
                            // Determine if this item should shift
                            val dist = (currentDragOffset / itemHeightPx).toInt()
                            val targetIndex = (currentDraggingIndex + dist).coerceIn(0, ruleSets.lastIndex)
                            
                            if (currentDraggingIndex < targetIndex) {
                                // Dragging down: items between current and target shift UP
                                if (index in (currentDraggingIndex + 1)..targetIndex) {
                                    translationY = -itemHeightPx
                                }
                            } else if (currentDraggingIndex > targetIndex) {
                                // Dragging up: items between target and current shift DOWN
                                if (index in targetIndex until currentDraggingIndex) {
                                    translationY = itemHeightPx
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .zIndex(zIndex)
                            .graphicsLayer {
                                this.translationY = translationY
                                if (index == currentDraggingIndex) {
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    shadowElevation = 8.dp.toPx()
                                }
                            }
                            .onGloballyPositioned { coordinates ->
                                if (itemHeightPx == 0f) {
                                    val spacingPx = with(density) { 16.dp.toPx() }
                                    itemHeightPx = coordinates.size.height.toFloat() + spacingPx
                                }
                            }
                            .then(
                                if (!enablePlacementAnimation || suppressPlacementAnimation) {
                                    Modifier
                                } else {
                                    Modifier.animateItemPlacement()
                                }
                            )
                    ) {
                        RuleSetItem(
                            ruleSet = ruleSet,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedItems[ruleSet.id] ?: false,
                            isDownloading = downloadingRuleSets.contains(ruleSet.tag),
                            onClick = {
                                if (isSelectionMode) {
                                    toggleSelection(ruleSet.id)
                                }
                            },
                            onEditClick = { editingRuleSet = ruleSet },
                            onDeleteClick = { settingsViewModel.deleteRuleSet(ruleSet.id) },
                            onOutboundClick = {
                                outboundEditingRuleSet = ruleSet
                                showOutboundModeDialog = true
                            },
                            onInboundClick = {
                                outboundEditingRuleSet = ruleSet
                                showInboundDialog = true
                            },
                            modifier = Modifier
                                .pointerInput(index) { // Key on index to ensure closure captures latest position
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            if (!isSelectionMode) {
                                                draggingItemIndex = index
                                                draggingItemOffset = 0f
                                                isDragging.value = true
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            }
                                        },
                                        onDragEnd = {
                                            if (draggingItemIndex != null) {
                                                val startIdx = draggingItemIndex!!
                                                val dist = kotlin.math.round(draggingItemOffset / itemHeightPx).toInt()
                                                val endIdx = (startIdx + dist).coerceIn(0, ruleSets.lastIndex)

                                                // Disable placement animation for the drop frame so the card settles immediately.
                                                suppressPlacementAnimation = true

                                                // Preserve absolute scroll position (px) to prevent list "jump" after reordering.
                                                // Anchoring by item-id causes a one-item "drop" when swapping with the first row.
                                                val absScrollBefore = if (itemHeightPx > 0f) {
                                                    listState.firstVisibleItemIndex * itemHeightPx + listState.firstVisibleItemScrollOffset
                                                } else {
                                                    null
                                                }
                                                
                                                if (startIdx != endIdx) {
                                                    // Immediately update UI list state to reflect final position
                                                    val item = ruleSets.removeAt(startIdx)
                                                    ruleSets.add(endIdx, item)
                                                    
                                                    // Asynchronously sync with ViewModel
                                                    settingsViewModel.reorderRuleSets(ruleSets.toList())
                                                }

                                                // Restore absolute scroll position (no animation).
                                                // This keeps the page from visually shifting when items above the viewport change.
                                                val abs = absScrollBefore
                                                if (abs != null && itemHeightPx > 0f) {
                                                    val targetIndex = (abs / itemHeightPx).toInt().coerceIn(0, ruleSets.lastIndex)
                                                    val targetOffset = (abs - targetIndex * itemHeightPx).toInt().coerceAtLeast(0)
                                                    scope.launch {
                                                        listState.scrollToItem(targetIndex, targetOffset)
                                                    }
                                                }
                                                
                                                // Reset drag state
                                                draggingItemIndex = null
                                                draggingItemOffset = 0f
                                                
                                                // IMPORTANT: Reset isDragging AFTER clearing indices to ensure
                                                // UI recomposes correctly without ghost overlays
                                                isDragging.value = false

                                                // Re-enable placement animation on next frame.
                                                scope.launch {
                                                    withFrameNanos { }
                                                    suppressPlacementAnimation = false
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            draggingItemIndex = null
                                            draggingItemOffset = 0f
                                            isDragging.value = false
                                            suppressPlacementAnimation = false
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            draggingItemOffset += dragAmount.y
                                        }
                                    )
                                }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RuleSetItem(
    ruleSet: RuleSet,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isDownloading: Boolean = false,
    onClick: () -> Unit,
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onOutboundClick: () -> Unit = {},
    onInboundClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除规则集",
            message = "确定要删除规则集 \"${ruleSet.tag}\" 吗？",
            confirmText = "删除",
            onConfirm = {
                onDeleteClick()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
    
    StandardCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { if (isSelectionMode) onClick() })
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = ruleSet.tag,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "下载中...",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    } else {
                        // 状态标签 - 绿色
                        Surface(
                            color = Color(0xFF2E7D32).copy(alpha = 0.8f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "已就绪",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // 出站标签 - 根据模式显示不同颜色
                    val outboundMode = ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT
                    val outboundText = when (outboundMode) {
                        RuleSetOutboundMode.DIRECT -> "直连"
                        RuleSetOutboundMode.BLOCK -> "拦截"
                        RuleSetOutboundMode.PROXY -> "代理"
                        RuleSetOutboundMode.NODE -> "节点"
                        RuleSetOutboundMode.PROFILE -> "配置"
                        RuleSetOutboundMode.GROUP -> "组"
                    }
                    val outboundColor = when (outboundMode) {
                        RuleSetOutboundMode.DIRECT -> Color(0xFF1565C0) // 蓝色
                        RuleSetOutboundMode.BLOCK -> Color(0xFFC62828) // 红色
                        RuleSetOutboundMode.PROXY -> Color(0xFF7B1FA2) // 紫色
                        RuleSetOutboundMode.NODE -> Color(0xFFE65100) // 橙色
                        RuleSetOutboundMode.PROFILE -> Color(0xFF00838F) // 青色
                        RuleSetOutboundMode.GROUP -> Color(0xFF6A1B9A) // 深紫色
                    }
                    Surface(
                        color = outboundColor.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = outboundText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // 入站标签 - 黄色/橙色
                    val inbounds = ruleSet.inbounds ?: emptyList()
                    val inboundText = if (inbounds.isEmpty()) "全部" else inbounds.joinToString(",")
                    Surface(
                        color = Color(0xFFFF8F00).copy(alpha = 0.8f), // 琥珀色
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = inboundText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${ruleSet.type.displayName} • ${ruleSet.format}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                if (ruleSet.type == RuleSetType.REMOTE) {
                    Text(
                        text = ruleSet.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = ruleSet.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
            }
            if (!isSelectionMode) {
                Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "更多选项",
                            tint = TextSecondary
                        )
                    }
                    MaterialTheme(
                        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier
                                .background(Neutral700)
                                .width(100.dp)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text("编辑", color = PureWhite)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    onEditClick()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text("删除", color = PureWhite)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirm = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text("出站", color = PureWhite)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    onOutboundClick()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text("入站", color = PureWhite)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    onInboundClick()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RuleSetEditorDialog(
    initialRuleSet: RuleSet? = null,
    onDismiss: () -> Unit,
    onConfirm: (RuleSet) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var tag by remember { mutableStateOf(initialRuleSet?.tag ?: "") }
    var type by remember { mutableStateOf(initialRuleSet?.type ?: RuleSetType.REMOTE) }
    var format by remember { mutableStateOf(initialRuleSet?.format ?: "binary") }
    var url by remember { mutableStateOf(initialRuleSet?.url ?: "") }
    var path by remember { mutableStateOf(initialRuleSet?.path ?: "") }
    
    var showTypeDialog by remember { mutableStateOf(false) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showTypeDialog) {
        val options = RuleSetType.entries.map { it.displayName }
        SingleSelectDialog(
            title = "规则集类型",
            options = options,
            selectedIndex = RuleSetType.entries.indexOf(type),
            onSelect = { index ->
                type = RuleSetType.entries[index]
                showTypeDialog = false
            },
            onDismiss = { showTypeDialog = false }
        )
    }

    if (showFormatDialog) {
        val options = listOf("binary", "source")
        SingleSelectDialog(
            title = "规则集格式",
            options = options,
            selectedIndex = options.indexOf(format).coerceAtLeast(0),
            onSelect = { index ->
                format = options[index]
                showFormatDialog = false
            },
            onDismiss = { showFormatDialog = false }
        )
    }
    
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除规则集",
            message = "确定要删除规则集 \"$tag\" 吗？",
            confirmText = "删除",
            onConfirm = {
                onDelete?.invoke()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = { 
            Text(
                text = if (initialRuleSet == null) "添加规则集" else "编辑规则集",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            ) 
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                StyledTextField(
                    label = "标识 (Tag)",
                    value = tag,
                    onValueChange = { tag = it },
                    placeholder = "geoip-cn"
                )

                ClickableDropdownField(
                    label = "类型",
                    value = type.displayName,
                    onClick = { showTypeDialog = true }
                )

                ClickableDropdownField(
                    label = "格式",
                    value = format,
                    onClick = { showFormatDialog = true }
                )

                if (type == RuleSetType.REMOTE) {
                    StyledTextField(
                        label = "URL",
                        value = url,
                        onValueChange = { url = it },
                        placeholder = "https://example.com/rules.srs"
                    )
                } else {
                    StyledTextField(
                        label = "本地路径",
                        value = path,
                        onValueChange = { path = it },
                        placeholder = "/path/to/rules.srs"
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newRuleSet = initialRuleSet?.copy(
                        tag = tag,
                        type = type,
                        format = format,
                        url = url,
                        path = path
                    ) ?: RuleSet(
                        tag = tag,
                        type = type,
                        format = format,
                        url = url,
                        path = path
                    )
                    onConfirm(newRuleSet)
                },
                enabled = tag.isNotBlank() && (if (type == RuleSetType.REMOTE) url.isNotBlank() else path.isNotBlank())
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (initialRuleSet != null && onDelete != null) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
fun DefaultRuleSetsDialog(
    existingTags: List<String>,
    onDismiss: () -> Unit,
    onAdd: (List<DefaultRuleSetConfig>) -> Unit
) {
    val selectedItems = remember { 
        mutableStateMapOf<String, Boolean>().apply {
            CHINA_DEFAULT_RULE_SETS.forEach { config ->
                put(config.tag, !existingTags.contains(config.tag))
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Neutral800,
        title = {
            Column {
                Text(
                    text = "添加默认规则集",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "适合中国大陆用户的常用规则",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(CHINA_DEFAULT_RULE_SETS) { config ->
                    val isExisting = existingTags.contains(config.tag)
                    val isSelected = selectedItems[config.tag] ?: false
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isExisting) {
                                selectedItems[config.tag] = !isSelected
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = if (isExisting) null else { isChecked -> selectedItems[config.tag] = isChecked },
                            enabled = !isExisting
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = config.tag,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isExisting) TextSecondary.copy(alpha = 0.5f) else TextPrimary
                            )
                            Text(
                                text = config.description + if (isExisting) " (已添加)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary.copy(alpha = if (isExisting) 0.5f else 1f)
                            )
                        }
                        val modeText = when (config.outboundMode) {
                            RuleSetOutboundMode.DIRECT -> "直连"
                            RuleSetOutboundMode.BLOCK -> "拦截"
                            RuleSetOutboundMode.PROXY -> "代理"
                            RuleSetOutboundMode.PROFILE -> "代理"
                            else -> ""
                        }
                        Surface(
                            color = when (config.outboundMode) {
                                RuleSetOutboundMode.DIRECT -> Color(0xFF2E7D32)
                                RuleSetOutboundMode.BLOCK -> Color(0xFFC62828)
                                RuleSetOutboundMode.PROXY -> Color(0xFF1565C0)
                                RuleSetOutboundMode.PROFILE -> Color(0xFF1565C0)
                                else -> Color.Gray
                            }.copy(alpha = if (isExisting) 0.3f else 0.8f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = modeText,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val selectedCount = selectedItems.count { it.value }
            TextButton(
                onClick = {
                    val selected = CHINA_DEFAULT_RULE_SETS.filter { selectedItems[it.tag] == true }
                    onAdd(selected)
                },
                enabled = selectedCount > 0
            ) {
                Text("添加 ($selectedCount)")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        CHINA_DEFAULT_RULE_SETS.forEach { config ->
                            if (!existingTags.contains(config.tag)) {
                                selectedItems[config.tag] = true
                            }
                        }
                    }
                ) {
                    Text("全选")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}