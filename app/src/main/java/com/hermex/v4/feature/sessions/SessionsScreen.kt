package com.hermex.v4.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermex.core.network.SessionSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).apply {
    timeZone = TimeZone.getDefault()
}

/** Best available "last message received" timestamp for a session (v0.1.82). */
private fun SessionSummary.lastActivityTs(): Double? =
    lastActivityAt ?: lastActive ?: startedAt

/** Compact relative time: 5m ago / 3h ago / 2d ago / absolute after 7d. */
private fun relativeTime(epochSeconds: Double): String {
    val now = System.currentTimeMillis()
    val ts = (epochSeconds * 1000).toLong()
    val diffMs = now - ts
    val minutes = diffMs / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> dateFormat.format(Date(ts))
    }
}

/** How many recent sessions show before the "All sessions" expander. */
private const val RECENT_LIMIT = 5

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    onSessionTap: (SessionSummary) -> Unit = {},
    onSettings: () -> Unit = {},
    activeSessions: StateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    onOpenCron: () -> Unit = {},
    onOpenBotScreen: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
    viewModel: SessionsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val pinnedIds by viewModel.pinnedIds.collectAsState()
    val activeMap by activeSessions.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var showAllSessions by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by rememberSaveable { mutableStateOf<SessionSummary?>(null) }
    // ── V4 home tree state (official app parity) ──
    var layoutGrouped by rememberSaveable { mutableStateOf(true) }
    var sortByName by rememberSaveable { mutableStateOf(false) }
    // Recent-sessions window: 0 = All, 1 = Today, 7 = 7d, 30 = 30d.
    var windowDays by rememberSaveable { mutableStateOf(0) }
    // agent key → how many children show (0 collapsed, HOME_CHILD_PEEK peek, -1 full)
    val agentExpand = remember { mutableStateMapOf<String, Int>() }
    var homeSeeded by rememberSaveable { mutableStateOf(false) }
    var rowMenuTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var showFiltersMenu by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }

    fun openSession(session: SessionSummary) {
        scope.launch { drawerState.close() }
        onSessionTap(session)
    }

    fun onDeleteSession(session: SessionSummary) {
        scope.launch { drawerState.close() }
        deleteTarget = session
        showDeleteConfirm = true
    }

    // Client-side search over the loaded list (mirrors desktop local filtering).
    // While searching, show ALL matches — the RECENT_LIMIT only applies to browsing.
    val q = query.trim()
    val visible = remember(state.sessions, q) {
        if (q.isEmpty()) {
            state.sessions
        } else {
            state.sessions.filter { s ->
                (s.title ?: "").contains(q, ignoreCase = true) ||
                    (s.preview ?: "").contains(q, ignoreCase = true) ||
                    s.id.contains(q, ignoreCase = true)
            }
        }
    }
    // Server orders session.list by last_active desc, so take(5) = 5 most recent.
    val pinned = visible.filter { it.id in pinnedIds }
    val unpinned = visible.filter { it.id !in pinnedIds }
    val recent = if (q.isEmpty()) unpinned.take(RECENT_LIMIT) else emptyList()
    val groups = remember(unpinned) {
        unpinned
            .groupBy { (it.source ?: "other").uppercase().ifEmpty { "OTHER" } }
            .entries
            .sortedByDescending { it.value.size }
    }

    // ── V4 home pipeline: window filter → search → agent groups / flat list ──
    val windowStartMs = remember(windowDays) {
        when (windowDays) {
            0 -> 0L
            1 -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            else -> System.currentTimeMillis() - windowDays.toLong() * 86_400_000L
        }
    }
    val homeVisible = remember(state.sessions, q, windowStartMs) {
        state.sessions.filter { s ->
            val ts = s.lastActivityTs()
            ts == null || (ts * 1000).toLong() >= windowStartMs
        }.filter { s ->
            q.isEmpty() ||
                (s.title ?: "").contains(q, ignoreCase = true) ||
                (s.preview ?: "").contains(q, ignoreCase = true) ||
                s.id.contains(q, ignoreCase = true) ||
                agentDisplayName(agentKeyFor(s)).contains(q, ignoreCase = true)
        }
    }
    val agentGroups = remember(homeVisible, sortByName) {
        homeVisible
            .groupBy { agentKeyFor(it) }
            .map { (key, list) ->
                val sorted = list.sortedByDescending { it.lastActivityTs() ?: 0.0 }
                AgentGroup(
                    key = key,
                    name = agentDisplayName(key),
                    description = "${list.size} session${if (list.size == 1) "" else "s"}" +
                        (sorted.firstOrNull()?.model?.let { " · $it" } ?: ""),
                    lastTs = sorted.maxOfOrNull { it.lastActivityTs() ?: 0.0 },
                    sessions = sorted,
                )
            }
            .sortedWith(
                if (sortByName) compareBy({ it.name.lowercase() }, { it.key })
                else compareByDescending<AgentGroup> { it.lastTs ?: 0.0 }
            )
    }
    val flatList = remember(homeVisible, sortByName) {
        if (sortByName) homeVisible.sortedBy { (it.title ?: it.id).lowercase() }
        else homeVisible.sortedByDescending { it.lastActivityTs() ?: 0.0 }
    }
    // Seed: expand the most recent agent group once, like the official app.
    LaunchedEffect(agentGroups.map { it.key }) {
        if (!homeSeeded && agentGroups.isNotEmpty()) {
            agentExpand[agentGroups.first().key] = HOME_CHILD_PEEK
            homeSeeded = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Header
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Text(
                            text = "Hermex",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${state.sessions.size} sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // New session
                    ListItem(
                        headlineContent = { Text("New session") },
                        leadingContent = {
                            Icon(Icons.Default.Add, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            if (!state.isCreating) {
                                viewModel.createSession { sid ->
                                    if (sid != null) {
                                        openSession(SessionSummary(id = sid, title = "New session"))
                                    }
                                }
                            }
                        },
                    )

                    // Search
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            // A new search resets the expander so matches are visible
                            if (it.isNotEmpty()) showAllSessions = false
                        },
                        placeholder = { Text("Search sessions…") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = MaterialTheme.shapes.large,
                    )

                    HorizontalDivider()

                    if (q.isNotEmpty()) {
                        // ── Search results: all matches, grouped ──
                        if (visible.isEmpty()) {
                            Text(
                                text = "No matches for \"$q\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        } else {
                            if (pinned.isNotEmpty()) {
                                SectionHeader("PINNED (${pinned.size})")
                                pinned.forEach { session ->
                                    DrawerSessionRow(
                                        session = session,
                                        pinned = true,
                                        onClick = { openSession(session) },
                                        onTogglePin = { viewModel.togglePin(session.id) },
                                        onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                    )
                                }
                            }
                            groups.forEach { (source, sessions) ->
                                SectionHeader("$source (${sessions.size})")
                                sessions.forEach { session ->
                                    DrawerSessionRow(
                                        session = session,
                                        pinned = false,
                                        onClick = { openSession(session) },
                                        onTogglePin = { viewModel.togglePin(session.id) },
                                        onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                    )
                                }
                            }
                        }
                    } else {
                        // ── Browse mode: pinned + recent 5 + expandable All ──
                        if (pinned.isNotEmpty()) {
                            SectionHeader("PINNED (${pinned.size})")
                            pinned.forEach { session ->
                                DrawerSessionRow(
                                    session = session,
                                    pinned = true,
                                    onClick = { openSession(session) },
                                    onTogglePin = { viewModel.togglePin(session.id) },
                                    onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                )
                            }
                            HorizontalDivider()
                        }

                        if (recent.isNotEmpty()) {
                            SectionHeader("RECENT")
                            recent.forEach { session ->
                                DrawerSessionRow(
                                    session = session,
                                    pinned = false,
                                    onClick = { openSession(session) },
                                    onTogglePin = { viewModel.togglePin(session.id) },
                                    onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                )
                            }
                            HorizontalDivider()
                        }

                        // Expandable full list
                        if (!showAllSessions) {
                            ListItem(
                                headlineContent = { Text("All sessions") },
                                supportingContent = { Text("${visible.size} total") },
                                trailingContent = {
                                    Icon(Icons.Default.ExpandMore, contentDescription = "Show all")
                                },
                                modifier = Modifier.clickable { showAllSessions = true },
                            )
                        } else {
                            groups.forEach { (source, sessions) ->
                                SectionHeader("$source (${sessions.size})")
                                sessions.forEach { session ->
                                    DrawerSessionRow(
                                        session = session,
                                        pinned = false,
                                        onClick = { openSession(session) },
                                        onTogglePin = { viewModel.togglePin(session.id) },
                                        onDeleted = { onDeleteSession(session) },
                                    isActive = activeMap[session.id] == true,
                                    )
                                }
                                HorizontalDivider()
                            }
                            ListItem(
                                headlineContent = { Text("Show less") },
                                trailingContent = {
                                    Icon(Icons.Default.ExpandLess, contentDescription = "Show less")
                                },
                                modifier = Modifier.clickable { showAllSessions = false },
                            )
                        }
                    }

                    // System panels (v0.1.61)
                    HorizontalDivider()
                    SectionHeader("SYSTEM")
                    ListItem(
                        headlineContent = { Text("Cron Jobs") },
                        leadingContent = {
                            Icon(Icons.Outlined.Schedule, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            scope.launch { drawerState.close() }
                            onOpenCron()
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Bot Screen") },
                        leadingContent = {
                            Icon(Icons.Outlined.Computer, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            scope.launch { drawerState.close() }
                            onOpenBotScreen()
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Skills & Tools") },
                        leadingContent = {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            scope.launch { drawerState.close() }
                            onOpenSkills()
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Config (core / soul)") },
                        leadingContent = {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            scope.launch { drawerState.close() }
                            onOpenConfig()
                        },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Hermex") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        // Official home top-right: Filters pill + More pill.
                        Box {
                            IconButton(onClick = { showFiltersMenu = true }) {
                                Icon(Icons.Outlined.Tune, contentDescription = "Filters")
                            }
                            DropdownMenu(
                                expanded = showFiltersMenu,
                                onDismissRequest = { showFiltersMenu = false },
                            ) {
                                HomeViewOptions(
                                    layoutGrouped = layoutGrouped,
                                    onLayoutGrouped = { layoutGrouped = it; showFiltersMenu = false },
                                    sortByName = sortByName,
                                    onSortByName = { sortByName = it; showFiltersMenu = false },
                                    windowDays = windowDays,
                                    onWindowDays = { windowDays = it; showFiltersMenu = false },
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                            ) {
                                HomeViewOptions(
                                    layoutGrouped = layoutGrouped,
                                    onLayoutGrouped = { layoutGrouped = it; showMoreMenu = false },
                                    sortByName = sortByName,
                                    onSortByName = { sortByName = it; showMoreMenu = false },
                                    windowDays = windowDays,
                                    onWindowDays = { windowDays = it; showMoreMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = { showMoreMenu = false; onSettings() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Refresh") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = { showMoreMenu = false; viewModel.loadSessions() },
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                // Official home bottom: full-width search pill.
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search agents and sessions") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            },
            floatingActionButton = {
                // Compose FAB: circle with pencil (official).
                FloatingActionButton(
                    onClick = {
                        if (!state.isCreating) {
                            viewModel.createSession { sid ->
                                if (sid != null) {
                                    onSessionTap(SessionSummary(id = sid, title = "New session"))
                                }
                            }
                        }
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    if (state.isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Edit, contentDescription = "New session")
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when {
                    state.error != null -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = state.error!!,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = viewModel::loadSessions) {
                                Text("Retry")
                            }
                        }
                    }
                    // V4 home = official-style agent-grouped session tree.
                    homeVisible.isEmpty() && !state.isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = if (q.isNotEmpty() || windowDays != 0)
                                    "No sessions match" else "No sessions yet",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (!state.isCreating) {
                                        viewModel.createSession { sid ->
                                            if (sid != null) {
                                                onSessionTap(SessionSummary(id = sid, title = "New session"))
                                            }
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("New session")
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                        ) {
                            if (!layoutGrouped) {
                                // Flat list layout (Filters → Layout → Flat list).
                                items(flatList, key = { "flat-${it.id}" }) { session ->
                                    ChildSessionRow(
                                        session = session,
                                        pinned = session.id in pinnedIds,
                                        active = activeMap[session.id] == true || session.isActive == true,
                                        showConnector = false,
                                        onClick = { onSessionTap(session) },
                                        onLongClick = { rowMenuTarget = session },
                                    )
                                }
                            } else {
                                item(key = "agents-header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "Agents",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                agentGroups.forEach { group ->
                                    val level = agentExpand[group.key] ?: 0
                                    item(key = "agent-${group.key}") {
                                        AgentGroupRow(
                                            group = group,
                                            expanded = level != 0,
                                            onClick = {
                                                agentExpand[group.key] =
                                                    if (level == 0) HOME_CHILD_PEEK else 0
                                            },
                                        )
                                    }
                                    if (level != 0) {
                                        val shown =
                                            if (level < 0) group.sessions
                                            else group.sessions.take(level)
                                        items(shown, key = { "s-${group.key}-${it.id}" }) { session ->
                                            ChildSessionRow(
                                                session = session,
                                                pinned = session.id in pinnedIds,
                                                active = activeMap[session.id] == true || session.isActive == true,
                                                showConnector = true,
                                                onClick = { onSessionTap(session) },
                                                onLongClick = { rowMenuTarget = session },
                                            )
                                        }
                                        val hidden = group.sessions.size - shown.size
                                        if (hidden > 0) {
                                            item(key = "more-${group.key}") {
                                                MoreSessionsRow(
                                                    hidden = hidden,
                                                    onClick = { agentExpand[group.key] = -1 },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Long-press row menu (pin / delete) — the tree rows have no trailing buttons ──
    rowMenuTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { rowMenuTarget = null },
            title = { Text(target.title ?: target.id.take(16)) },
            text = {
                Column {
                    Text(
                        "Pin to drawer",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.togglePin(target.id)
                                rowMenuTarget = null
                            }
                            .padding(vertical = 12.dp),
                    )
                    Text(
                        "Delete session",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                rowMenuTarget = null
                                onDeleteSession(target)
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { rowMenuTarget = null }) { Text("Close") }
            },
        )
    }

    // ── Delete confirmation dialog ──
    if (showDeleteConfirm && deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete session?") },
            text = {
                Text(
                    "Delete \"${deleteTarget!!.title ?: deleteTarget!!.id.take(16)}\"? " +
                    "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTarget!!
                        showDeleteConfirm = false
                        viewModel.deleteSession(target.id) {
                            // list will reload automatically
                        }
                    },
                    enabled = !state.deleting,
                ) {
                    if (state.deleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Deleting…")
                    } else {
                        Text("Delete")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleted: () -> Unit,
    isActive: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = session.title ?: session.id.take(16),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    session.lastActivityTs()?.let { ts ->
                        Text(
                            text = relativeTime(ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                session.preview?.let { p ->
                    if (p.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = p,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                session.lastActivityDescription?.let { desc ->
                    if (desc.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Last message: $desc",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "working",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    session.model?.let { m ->
                        Text(
                            text = m,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "${session.messageCount} msgs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    session.source?.let { src ->
                        Text(
                            text = src,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (pinned) "Unpin" else "Pin",
                    tint = if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = onDeleted,
                enabled = !isActive,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = if (isActive) "Cannot delete active session" else "Delete",
                    tint = if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun DrawerSessionRow(
    session: SessionSummary,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleted: () -> Unit,
    isActive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title ?: session.id.take(16),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isActive) {
                Text(
                    text = "● working…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            session.preview?.let { p ->
                if (p.isNotBlank()) {
                    Text(
                        text = p,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (isActive) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (pinned) "Unpin" else "Pin",
                tint = if (pinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(
            onClick = onDeleted,
            enabled = !isActive,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = if (isActive) "Cannot delete active session" else "Delete",
                tint = if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// V4 official-style home: agent-grouped session tree
// ═════════════════════════════════════════════════════════════════════════

/** Children shown per agent before the "N more sessions" expander. */
private const val HOME_CHILD_PEEK = 2

/**
 * One agent row. The gateway's `session.list` carries no agent/profile field
 * (probe 2026-11-04: rows have only source/model/timestamps), so the agent
 * identity degrades to the session `source` (gui/api/cli/cron/tui/ntfy/desktop)
 * rather than fabricating agent metadata.
 */
private data class AgentGroup(
    val key: String,
    val name: String,
    val description: String,
    val lastTs: Double?,
    val sessions: List<SessionSummary>,
)

private fun agentKeyFor(s: SessionSummary): String =
    (s.source?.takeIf { it.isNotBlank() } ?: "other").lowercase()

private fun agentDisplayName(key: String): String = when (key) {
    "gui" -> "Gui"
    "tui" -> "Tui"
    "api" -> "Api"
    "cli" -> "Cli"
    "cron" -> "Cron"
    "ntfy" -> "Ntfy"
    "desktop" -> "Desktop"
    else -> key.replaceFirstChar { it.uppercaseChar() }
}

private val agentAvatarColors = listOf(
    Color(0xFF7C4DFF), Color(0xFF26C6DA), Color(0xFF66BB6A), Color(0xFFAB47BC),
    Color(0xFFFF7043), Color(0xFF42A5F5), Color(0xFF9CCC65), Color(0xFF5C6BC0),
)

private fun avatarColorFor(key: String): Color =
    agentAvatarColors[(key.hashCode() and Int.MAX_VALUE) % agentAvatarColors.size]

private fun initialsFor(name: String): String =
    name.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "A" }

/** Shared Filters/More menu body: Layout, Sort, Recent-sessions window. */
@Composable
private fun HomeViewOptions(
    layoutGrouped: Boolean,
    onLayoutGrouped: (Boolean) -> Unit,
    sortByName: Boolean,
    onSortByName: (Boolean) -> Unit,
    windowDays: Int,
    onWindowDays: (Int) -> Unit,
) {
    DropdownMenuItem(
        text = { Text("Layout") },
        enabled = false,
        onClick = {},
    )
    DropdownMenuItem(
        text = { Text("Group by agent") },
        trailingIcon = { if (layoutGrouped) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = { onLayoutGrouped(true) },
    )
    DropdownMenuItem(
        text = { Text("Flat list") },
        trailingIcon = { if (!layoutGrouped) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = { onLayoutGrouped(false) },
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text("Sort") },
        enabled = false,
        onClick = {},
    )
    DropdownMenuItem(
        text = { Text("Recent activity") },
        trailingIcon = { if (!sortByName) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = { onSortByName(false) },
    )
    DropdownMenuItem(
        text = { Text("Name") },
        trailingIcon = { if (sortByName) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = { onSortByName(true) },
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text("Recent sessions") },
        enabled = false,
        onClick = {},
    )
    listOf(
        1 to "Today",
        7 to "Last 7 days",
        30 to "Last 30 days",
        0 to "All time",
    ).forEach { (days, label) ->
        DropdownMenuItem(
            text = { Text(label) },
            trailingIcon = { if (windowDays == days) Icon(Icons.Default.Check, contentDescription = null) },
            onClick = { onWindowDays(days) },
        )
    }
}

/** Agent header row: squircle avatar + name + description + right time + chevron. */
@Composable
private fun AgentGroupRow(
    group: AgentGroup,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(avatarColorFor(group.key)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialsFor(group.name),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = group.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        group.lastTs?.let { ts ->
            Text(
                text = relativeTime(ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowDown
            else Icons.Default.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Child session row: connector + status dot + title + right time + chevron. */
@Composable
private fun ChildSessionRow(
    session: SessionSummary,
    pinned: Boolean,
    active: Boolean,
    showConnector: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = if (showConnector) 24.dp else 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showConnector) {
            // Tree connector: short vertical hairline, official style.
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(26.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                )
            }
        }
        // Live status: blue spinner while the gateway/VM reports the session
        // working; a quiet blue dot otherwise. No status known → dot stays blue
        // (official shows one on every row) — never a fabricated "working".
        if (active) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title ?: session.id.take(16),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            session.preview?.let { p ->
                if (p.isNotBlank()) {
                    Text(
                        text = p,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (active) {
            Text(
                text = "Working",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            session.lastActivityTs()?.let { ts ->
                Text(
                    text = relativeTime(ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "N more sessions >" expander row, indented under its agent. */
@Composable
private fun MoreSessionsRow(hidden: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = 50.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$hidden more session${if (hidden == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = "Show more",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
