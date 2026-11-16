package com.rkacast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.rkacast.cast.MediaResolver
import com.rkacast.data.Settings
import com.rkacast.stremio.AddonRepository
import com.rkacast.stremio.AddonStream
import com.rkacast.stremio.CatalogDef
import com.rkacast.stremio.InstalledAddon
import com.rkacast.stremio.Meta
import com.rkacast.stremio.MetaPreview
import com.rkacast.stremio.SearchService
import com.rkacast.stremio.StremioClient
import com.rkacast.stremio.Subtitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface StremioRoute {
    data object Discover : StremioRoute
    data class Search(val query: String) : StremioRoute
    data class Detail(val type: String, val id: String, val preview: MetaPreview?) : StremioRoute
    data class Streams(val type: String, val videoId: String, val title: String, val poster: String?) : StremioRoute
    data object Addons : StremioRoute
}

private val stack = mutableStateListOf<StremioRoute>(StremioRoute.Discover)

@Composable
fun StremioTab(app: AppState) {
    val addons by AddonRepository.addons.collectAsState()
    LaunchedEffect(app.pendingStremioAddon) {
        if (app.pendingStremioAddon != null && stack.lastOrNull() != StremioRoute.Addons) stack.add(StremioRoute.Addons)
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    val route = stack.last()
    val push: (StremioRoute) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    if (addons.isEmpty() && route !is StremioRoute.Addons) {
        StremioOnboarding(app, push)
        return
    }
    when (route) {
        StremioRoute.Discover -> DiscoverScreen(addons, push)
        is StremioRoute.Search -> SearchScreen(addons, route.query, push, pop)
        is StremioRoute.Detail -> DetailScreen(app, addons, route, push, pop)
        is StremioRoute.Streams -> StreamsScreen(app, addons, route, pop)
        StremioRoute.Addons -> AddonsScreen(app, addons, pop)
    }
}

@Composable
private fun BackHeader(title: String, pop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        IconButton(onClick = pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StremioOnboarding(app: AppState, push: (StremioRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Stremio", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Browse movies and shows with Stremio addons, then cast any stream, including torrents, to your TV.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                installing = true
                scope.launch {
                    for ((_, url) in AddonRepository.suggested.take(3)) runCatching { AddonRepository.install(url) }
                    installing = false
                    if (AddonRepository.isEmpty) app.message("Couldn't reach the addons. Check your internet connection.")
                }
            },
            enabled = !installing, modifier = Modifier.fillMaxWidth(),
        ) { Text(if (installing) "Installing…" else "Quick setup (Cinemeta + Torrentio + OpenSubtitles)") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { push(StremioRoute.Addons) }, modifier = Modifier.fillMaxWidth()) {
            Text("Log in to Stremio or add addons manually")
        }
    }
}

// ------------------------------------------------------------------------------------------- Discover

private data class CatalogRow(val addon: InstalledAddon, val catalog: CatalogDef)

@Composable
private fun DiscoverScreen(addons: List<InstalledAddon>, push: (StremioRoute) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(addons) {
        addons.flatMap { a -> a.manifest.catalogs.filter { !it.requiresExtra }.map { CatalogRow(a, it) } }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = {
                        query = it
                        if (it.length >= 2) { push(StremioRoute.Search(it)); query = "" }
                    },
                    modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Search movies & series") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) push(StremioRoute.Search(query.trim())) }),
                )
                IconButton(onClick = { push(StremioRoute.Addons) }) { Icon(Icons.Default.Extension, "Addons") }
            }
        }
        items(rows, key = { "${it.addon.manifest.id}/${it.catalog.type}/${it.catalog.id}" }) { row ->
            CatalogRowView(row, push)
        }
    }
}

@Composable
private fun CatalogRowView(row: CatalogRow, push: (StremioRoute) -> Unit) {
    var items by remember { mutableStateOf<List<MetaPreview>?>(null) }
    LaunchedEffect(row) {
        items = runCatching { StremioClient.catalog(row.addon, row.catalog) }.getOrDefault(emptyList())
    }
    val list = items
    if (list != null && list.isEmpty()) return
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            "${row.catalog.name ?: row.catalog.id} · ${row.catalog.type.replaceFirstChar { it.uppercase() }}",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                items(list, key = { it.id }) { m ->
                    Poster(m, Modifier.width(112.dp).padding(horizontal = 4.dp)) { push(StremioRoute.Detail(m.type, m.id, m)) }
                }
            }
        }
    }
}

@Composable
private fun Poster(m: MetaPreview, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = m.poster, contentDescription = m.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (m.posterShape == "landscape") 16f / 9 else 2f / 3)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(m.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------------------------------- Search

@Composable
private fun SearchScreen(addons: List<InstalledAddon>, initialQuery: String, push: (StremioRoute) -> Unit, pop: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initialQuery, TextRange(initialQuery.length))) }
    val query = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var results by remember { mutableStateOf<SearchService.Results?>(null) }
    var loading by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("all") }

    // Search as you type, debounced.
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = null; return@LaunchedEffect }
        delay(350)
        loading = true
        results = SearchService.search(addons, query)
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(
                value = field, onValueChange = { field = it }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("Search movies & series") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            listOf("all" to "All", "movie" to "Movies", "series" to "Series").forEach { (k, label) ->
                FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        val r = results
        when {
            r == null && !loading -> Text(
                "Type at least 2 letters. Typos are OK.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            r.movies.isEmpty() && r.series.isEmpty() && r.other.isEmpty() -> Text("No results for \"$query\"", Modifier.padding(16.dp))
            else -> {
                val list = when (filter) {
                    "movie" -> r.movies
                    "series" -> r.series
                    else -> (r.movies + r.series + r.other)
                }
                if (filter == "all") {
                    LazyColumn {
                        if (r.movies.isNotEmpty()) item { ResultRow("Movies", r.movies, push) }
                        if (r.series.isNotEmpty()) item { ResultRow("Series", r.series, push) }
                        if (r.other.isNotEmpty()) item { ResultRow("Other", r.other, push) }
                    }
                } else {
                    LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                        items(list, key = { it.id }) { m -> Poster(m, Modifier.padding(4.dp)) { push(StremioRoute.Detail(m.type, m.id, m)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(title: String, items: List<MetaPreview>, push: (StremioRoute) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(6.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            items(items, key = { it.id }) { m ->
                Column(Modifier.width(112.dp).padding(horizontal = 4.dp)) {
                    Poster(m, Modifier.fillMaxWidth()) { push(StremioRoute.Detail(m.type, m.id, m)) }
                    m.releaseInfo?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- Detail

@Composable
private fun DetailScreen(
    app: AppState,
    addons: List<InstalledAddon>,
    route: StremioRoute.Detail,
    push: (StremioRoute) -> Unit,
    pop: () -> Unit,
) {
    var meta by remember(route) { mutableStateOf<Meta?>(null) }
    var loading by remember(route) { mutableStateOf(true) }
    LaunchedEffect(route) {
        meta = runCatching { StremioClient.meta(addons, route.type, route.id) }.getOrNull()
        loading = false
    }
    val m = meta
    val name = m?.name ?: route.preview?.name ?: route.id
    val poster = m?.poster ?: route.preview?.poster
    val seasons = remember(m) { m?.videos?.mapNotNull { it.season }?.distinct()?.sorted() ?: emptyList() }
    var season by remember(seasons) { mutableStateOf(seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull()) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackHeader(name, pop)
            Box {
                AsyncImage(
                    model = m?.background ?: poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9),
                )
            }
            Column(Modifier.padding(16.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val info = listOfNotNull(m?.releaseInfo ?: route.preview?.releaseInfo, m?.runtime, (m?.imdbRating ?: route.preview?.imdbRating)?.let { "★ $it" })
                if (info.isNotEmpty()) Text(info.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                m?.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                (m?.description ?: route.preview?.description)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(12.dp))
                if (loading) CircularProgressIndicator()
                if (!loading && (m == null || m.videos.isEmpty() || route.type == "movie")) {
                    Button(onClick = {
                        val vid = if (route.type == "movie" || m?.videos.isNullOrEmpty()) route.id else m!!.videos.first().id
                        push(StremioRoute.Streams(route.type, vid, name, poster))
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Cast, null); Text("  Find streams") }
                }
            }
            if (seasons.size > 1) {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                    items(seasons) { s ->
                        FilterChip(
                            selected = s == season, onClick = { season = s },
                            label = { Text(if (s == 0) "Specials" else "Season $s") },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
        }
        if (m != null && route.type != "movie") {
            val eps = m.videos.filter { season == null || it.season == season }.sortedBy { it.episode ?: 0 }
            items(eps, key = { it.id }) { v ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val label = if (v.season != null) "$name S${v.season}E${v.episode} · ${v.label}" else "$name · ${v.label}"
                        push(StremioRoute.Streams(route.type, v.id, label, v.thumbnail ?: poster))
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(120.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${v.episode ?: ""}. ${v.label}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        v.released?.take(10)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- Streams

@Composable
private fun StreamsScreen(app: AppState, addons: List<InstalledAddon>, route: StremioRoute.Streams, pop: () -> Unit) {
    val ctx = app.context
    val preferredLang by Settings.subtitleLang.collectAsState()
    var streams by remember(route) { mutableStateOf<List<AddonStream>?>(null) }
    var subs by remember(route) { mutableStateOf<List<Subtitle>>(emptyList()) }
    var chosenSub by remember(route) { mutableStateOf<SubtitlePick?>(null) }

    LaunchedEffect(route) {
        app.nextSubtitle = null
        launch {
            subs = runCatching { StremioClient.subtitles(addons, route.type, route.videoId) }.getOrDefault(emptyList())
            app.subtitleOptions = subs
            subs.firstOrNull { it.lang == preferredLang }?.let { chosenSub = SubtitlePick(languageName(it.lang), it.url) }
        }
        streams = runCatching { StremioClient.streams(addons, route.type, route.videoId) }.getOrDefault(emptyList())
    }
    // When the shared picker is used from this screen, it writes to app.nextSubtitle.
    LaunchedEffect(app.nextSubtitle) { app.nextSubtitle?.let { chosenSub = it } }

    Column(Modifier.fillMaxSize()) {
        BackHeader(route.title, pop)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = { app.subtitleOptions = subs; app.showSubtitlePicker = true },
                label = { Text(chosenSub?.let { "Subtitles: ${it.label}" } ?: if (subs.isEmpty()) "Subtitles: none found" else "Subtitles: off") },
                leadingIcon = { Icon(Icons.Default.ClosedCaption, null, Modifier.size(18.dp)) },
            )
            if (chosenSub != null) TextButton(onClick = { chosenSub = null; app.nextSubtitle = null }) { Text("Turn off") }
        }
        val list = streams
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(
                "No streams found. Install a stream addon such as Torrentio from the Addons screen.",
                Modifier.padding(16.dp),
            )
            else -> LazyColumn {
                items(list) { s ->
                    val playable = s.stream.url != null || s.stream.infoHash != null
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(s.addonName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(s.stream.label, style = MaterialTheme.typography.bodyMedium)
                        if (s.stream.isTorrent) Text("Torrent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Row {
                            TextButton(enabled = playable, onClick = {
                                val sub = chosenSub?.source
                                app.cast(if (s.stream.isTorrent) "Starting torrent…" else "Sending to TV…") {
                                    MediaResolver.fromStremio(ctx, s.stream, route.title, route.poster, sub)
                                }
                            }) { Icon(Icons.Default.Cast, null); Text(" Cast") }
                            TextButton(enabled = playable, onClick = {
                                val sub = chosenSub?.source
                                app.playOnPhone(if (s.stream.isTorrent) "Starting torrent…" else "Opening…") {
                                    MediaResolver.fromStremio(ctx, s.stream, route.title, route.poster, sub)
                                }
                            }) { Icon(Icons.Default.PhoneAndroid, null); Text(" Play here") }
                            if (!playable && s.stream.externalUrl != null) {
                                TextButton(onClick = { app.pendingBrowserUrl = s.stream.externalUrl; app.tab = Tab.BROWSER }) { Text("Open link") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- Addons

@Composable
private fun AddonsScreen(app: AppState, addons: List<InstalledAddon>, pop: () -> Unit) {
    val scope = rememberCoroutineScope()
    val authKey by Settings.stremioAuthKey.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var confirmUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(app.pendingStremioAddon) {
        app.pendingStremioAddon?.let { confirmUrl = it; app.pendingStremioAddon = null }
    }

    fun install(u: String) {
        busy = true
        scope.launch {
            runCatching { AddonRepository.install(u) }
                .onSuccess { app.message("Installed ${it.manifest.name}") }
                .onFailure { app.message("Couldn't install addon: ${it.message}") }
            busy = false
        }
    }

    fun sync(key: String) {
        busy = true
        scope.launch {
            runCatching { StremioClient.accountAddons(key) }
                .onSuccess {
                    AddonRepository.replaceAll(it)
                    app.message("Synced ${it.size} addons from your Stremio account")
                }
                .onFailure { app.message("Sync failed: ${it.message}") }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackHeader("Addons", pop)
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Stremio account", style = MaterialTheme.typography.titleMedium)
                    if (authKey == null) {
                        Text("Log in to import the addons from your Stremio account.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showLogin = true }) { Text("Log in") }
                    } else {
                        Text("Logged in", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { sync(authKey!!) }, enabled = !busy) { Text("Sync addons") }
                            TextButton(onClick = { Settings.setStremioAuthKey(null) }) { Text("Log out") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("Addon URL (…/manifest.json)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { install(url); url = "" }, enabled = url.isNotBlank() && !busy) { Text("Add") }
            }
            if (busy) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Spacer(Modifier.height(8.dp))
            Text("Suggested", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
            AddonRepository.suggested.forEach { (label, u) ->
                val installed = addons.any { it.transportUrl == StremioClient.normalizeAddonUrl(u) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { install(u) }, enabled = !installed && !busy) { Text(if (installed) "Installed" else "Install") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Installed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        items(addons, key = { it.transportUrl }) { a ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = a.manifest.logo, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.manifest.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.manifest.description ?: a.transportUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { AddonRepository.move(a, up = true) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { AddonRepository.move(a, up = false) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                IconButton(onClick = { AddonRepository.remove(a) }) { Icon(Icons.Default.Delete, "Remove") }
            }
        }
    }

    if (showLogin) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showLogin = false },
            title = { Text("Log in to Stremio") },
            text = {
                Column {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogin = false
                    busy = true
                    scope.launch {
                        runCatching { StremioClient.login(email.trim(), password) }
                            .onSuccess { key -> Settings.setStremioAuthKey(key); busy = false; sync(key) }
                            .onFailure { app.message("Login failed: ${it.message}"); busy = false }
                    }
                }, enabled = email.isNotBlank() && password.isNotBlank()) { Text("Log in") }
            },
            dismissButton = { TextButton(onClick = { showLogin = false }) { Text("Cancel") } },
        )
    }

    confirmUrl?.let { u ->
        AlertDialog(
            onDismissRequest = { confirmUrl = null },
            title = { Text("Install addon?") },
            text = { Text(StremioClient.normalizeAddonUrl(u)) },
            confirmButton = { TextButton(onClick = { confirmUrl = null; install(u) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { confirmUrl = null }) { Text("Cancel") } },
        )
    }
}
