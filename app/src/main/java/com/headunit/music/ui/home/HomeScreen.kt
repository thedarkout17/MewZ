package com.headunit.music.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import com.headunit.music.data.HISTORY_ID
import com.headunit.music.data.LIKED_ID
import com.headunit.music.data.Library
import com.headunit.music.data.LibraryStore
import com.headunit.music.ui.library.LibraryGrid
import com.headunit.music.ui.library.LibraryPage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.headunit.music.R
import com.headunit.music.data.LocalSuggestionProvider
import com.headunit.music.data.SearchHistory
import com.headunit.music.data.SuggestionProvider
import com.headunit.music.data.Track
import com.headunit.music.data.youtube.SearchResult
import com.headunit.music.data.youtube.YouTubeSearch
import com.headunit.music.ui.components.BAR_HEIGHT_COMPACT
import com.headunit.music.ui.components.BAR_HEIGHT_IDLE
import com.headunit.music.ui.components.SearchBar
import com.headunit.music.ui.keyboard.GlassKeyboard
import com.headunit.music.ui.keyboard.KEYBOARD_HEIGHT
import com.headunit.music.ui.theme.DesignCanvas
import com.headunit.music.ui.theme.HeadunitMusicTheme
import com.headunit.music.ui.theme.LocalDesignScale
import kotlinx.coroutines.delay

private const val MAX_SUGGESTIONS = 14 // same as YouTube's suggestion list
/** While typing, at most this many past searches lead the list; the rest are suggestions. */
private const val MAX_HISTORY_WHILE_TYPING = 2
private const val SUGGEST_DEBOUNCE_MS = 120L

/** How many past searches the empty search bar lists (scrollable). */
private const val MAX_RECENT_SHOWN = 30

/** How much of the first library row peeks up from the bottom of the idle screen (design px). */
private const val LIBRARY_PEEK = 110f

/**
 * Home screen.
 *
 * Idle: gradient background, search pill, script "Listen..." in the middle.
 *
 * Searching (bar tapped): "Listen..." zooms out, the background frosts over,
 * the bar rises and slims down, and our glass keyboard slides up. Suggestions
 * appear as you type: three above the keyboard, the last two blurred behind it.
 * The ⌄ key or a tap on empty space puts the keyboard away to show all five;
 * tap the bar to bring it back.
 *
 * Recent searches: opening the bar with nothing typed lists all past searches
 * in a scrollable list (clock icon, × to remove). While typing, matching past searches lead the list.
 *
 * Results: sending a search (Search key, a suggestion, or voice) closes the
 * panel and lists YouTube songs under the bar. Tap the bar to search again;
 * back from results returns to the idle home screen.
 *
 * Libraries: scroll down from "Listen..." to big glass squares, two per row
 * (Liked, History, yours, then + to make one). Tapping one opens its songs
 * under its name; nothing plays until you pick a song.
 */
@Composable
fun HomeScreen(
    youtube: YouTubeSearch,
    history: SearchHistory,
    libraries: LibraryStore,
    /**
     * A song was tapped: the whole list, the tapped position, and the library's
     * name (null for search results, which aren't a collection).
     */
    onPlay: (tracks: List<Track>, index: Int, collection: String?) -> Unit,
    /** The + tile: name a new library. */
    onCreateLibrary: () -> Unit,
    /** A song (in search results or a library) was held: add it to the queue. */
    onQueue: (Track) -> Unit = {},
    onVoiceSearch: () -> Unit,
    voiceResult: String? = null,
    onVoiceResultConsumed: () -> Unit = {},
    suggestionProvider: SuggestionProvider = remember { LocalSuggestionProvider() },
    /** Height (design px) of the mini player docked at the bottom; content stays clear of it. */
    dockInset: Float = 0f,
    /** Tells the player dock to slide away while our keyboard is up. */
    onKeyboardShownChange: (Boolean) -> Unit = {},
    /** Bump this to send the screen back to the idle "Listen..." home (e.g. after minimizing the player). */
    goHomeRequests: Int = 0,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(false) }       // search panel open
    var keyboard by rememberSaveable { mutableStateOf(false) }   // our keyboard showing
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var recent by remember { mutableStateOf(history.load()) }

    // What the list under the bar shows: recent searches when nothing is typed;
    // otherwise matching recent searches first, then suggestions.
    val listItems = if (query.isBlank()) {
        recent.take(MAX_RECENT_SHOWN).map { SuggestionItem(it, fromHistory = true) }
    } else {
        val past = history.matching(recent, query).take(MAX_HISTORY_WHILE_TYPING)
        val fresh = suggestions.filterNot { s -> past.any { it.equals(s, ignoreCase = true) } }
        (past.map { SuggestionItem(it, fromHistory = true) } +
            fresh.map { SuggestionItem(it, fromHistory = false) }).take(MAX_SUGGESTIONS)
    }

    // Last search sent, and its results. `attempt` bumps to retry the same query.
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<ResultsState>(ResultsState.Loading) }

    // Library page open (by id, so it updates as songs are added).
    var openLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    val openLibrary: Library? = openLibraryId?.let { libraries.byId(it) }
    // Kept for the closing animation after the page is dismissed.
    var lastLibrary by remember { mutableStateOf<Library?>(null) }
    SideEffect { if (openLibrary != null) lastLibrary = openLibrary }
    val homeScroll = rememberScrollState()

    val searching = open || query.isNotEmpty() || submitted != null
    val showResults = submitted != null && !open
    val keyboardShown = open && keyboard

    val progress by animateFloatAsState(
        targetValue = if (searching) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "searchMode",
    )
    val libP by animateFloatAsState(
        targetValue = if (openLibrary != null) 1f else 0f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "library",
    )
    // Frosted background: searching or in a library.
    val frost = maxOf(progress, libP)
    val kb by animateFloatAsState(
        targetValue = if (keyboardShown) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "keyboard",
    )

    fun activate() { open = true; keyboard = true }
    fun submit(text: String) {
        query = text
        open = false
        keyboard = false
        recent = history.add(text)
        if (text == submitted) attempt++ else submitted = text
    }
    /** Close the search panel; if a search is showing, the bar goes back to its query. */
    fun closePanel() {
        open = false
        keyboard = false
        submitted?.let { query = it }
    }
    fun tapOutside() {
        if (!open) return
        if (keyboard && query.isNotEmpty()) keyboard = false // first tap: put the keyboard away
        else closePanel()                                    // then: close suggestions
    }
    fun goHome() {
        openLibraryId = null
        query = ""
        submitted = null
        open = false
        keyboard = false
    }

    // When the mic returns text, drop it in the box and search straight away.
    LaunchedEffect(voiceResult) {
        if (!voiceResult.isNullOrBlank()) {
            submit(voiceResult)
            onVoiceResultConsumed()
        }
    }

    // Run the search whenever a new query is sent (or Try again is tapped).
    LaunchedEffect(submitted, attempt) {
        val q = submitted ?: return@LaunchedEffect
        results = ResultsState.Loading
        results = when (val r = youtube.search(q)) {
            is SearchResult.Success -> ResultsState.Done(q, r.tracks)
            is SearchResult.Failure -> ResultsState.Failed(r.reason)
        }
    }

    // Fetch suggestions as the query changes (short debounce so fast typing doesn't thrash).
    LaunchedEffect(query) {
        if (query.isBlank()) {
            suggestions = emptyList()
        } else {
            delay(SUGGEST_DEBOUNCE_MS)
            suggestions = suggestionProvider.suggest(query, MAX_SUGGESTIONS)
        }
    }

    LaunchedEffect(keyboardShown) { onKeyboardShownChange(keyboardShown) }

    LaunchedEffect(goHomeRequests) { if (goHomeRequests > 0) goHome() }

    // Back: panel open over results → back to results; otherwise → idle home.
    BackHandler(enabled = searching || openLibraryId != null) {
        when {
            openLibraryId != null -> openLibraryId = null
            open && submitted != null -> closePanel()
            else -> goHome()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Sharp background, always there.
        Image(
            painter = painterResource(R.drawable.home_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Frosted glass: a pre-blurred copy (works on every Android version,
        // unlike Modifier.blur which needs Android 12) plus a light veil.
        if (frost > 0f) {
            Image(
                painter = painterResource(R.drawable.home_background_blur),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = frost
                        // Settles from slightly zoomed to rest, so the glass feels like it lands.
                        val s = 1.04f - 0.04f * frost
                        scaleX = s; scaleY = s
                    },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = frost }
                    .background(Color(0x1F2A0006)), // faint dark-red tint helps white text
            )
        }

        DesignCanvas(Modifier.fillMaxSize()) {
            with(LocalDesignScale.current) {
                // Bar rises and slims down as search opens; suggestions follow it.
                fun lerp(idle: Float, searching: Float) = idle + (searching - idle) * progress
                val barTop = lerp(64f, 28f)
                val barHeight = lerp(BAR_HEIGHT_IDLE, BAR_HEIGHT_COMPACT)
                val screenHeightPx = constraints.maxHeight.toFloat()
                val keyboardHeightPx = with(LocalDensity.current) { KEYBOARD_HEIGHT.u.toPx() }

                // Taps on empty space while searching.
                if (searching) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = ::tapOutside,
                            ),
                    )
                }

                val home = (1f - progress) * (1f - libP) // idle home showing (1) or not (0)
                val scrollFadePx = with(LocalDensity.current) { 520.u.toPx() }

                // "Listen..." zooms out and fades away; scrolls up and away to the libraries.
                if (progress < 1f && libP < 1f) {
                    Image(
                        painter = painterResource(R.drawable.listen_title),
                        contentDescription = "Listen",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = 20.u)
                            .width(1090.u)
                            .graphicsLayer {
                                val s = 1f - 0.35f * progress
                                scaleX = s; scaleY = s
                                translationY = -homeScroll.value.toFloat()
                                alpha = home * (1f - homeScroll.value / scrollFadePx).coerceIn(0f, 1f)
                            },
                    )

                    // Libraries, under "Listen...": the page scrolls, the search bar stays put.
                    val screenH = maxHeight / 1.u
                    val barBottom = 64f + BAR_HEIGHT_IDLE
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = home
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            // Tiles dissolve as they pass under the search bar.
                            .drawWithContent {
                                drawContent()
                                val clear = (barBottom - 30f).u.toPx() / size.height
                                val solid = (barBottom + 80f).u.toPx() / size.height
                                drawRect(
                                    Brush.verticalGradient(0f to Color.Transparent, clear to Color.Transparent, solid to Color.Black),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                            .verticalScroll(homeScroll, enabled = !searching && openLibraryId == null),
                    ) {
                        Spacer(Modifier.height((screenH - LIBRARY_PEEK).u))
                        LibraryGrid(
                            liked = libraries.byId(LIKED_ID)!!,
                            history = libraries.byId(HISTORY_ID)!!,
                            custom = libraries.custom,
                            onOpen = { openLibraryId = it.id },
                            onCreate = onCreateLibrary,
                        )
                        Spacer(Modifier.height((dockInset + 40f).u))
                    }
                }

                // A library's songs.
                val pageLibrary = openLibrary ?: lastLibrary
                if (libP > 0f && pageLibrary != null) {
                    LibraryPage(
                        library = pageLibrary,
                        onBack = { openLibraryId = null },
                        // Libraries play in a loop, showing "Name — 3 of 20" on Now Playing.
                        onPlay = { i -> onPlay(pageLibrary.tracks, i, pageLibrary.name) },
                        onQueue = onQueue,
                        dockInset = dockInset,
                        modifier = Modifier.graphicsLayer {
                            alpha = libP
                            translationY = (1f - libP) * 48.u.toPx()
                        },
                    )
                }

                // Results fill the space under the bar.
                // (Not composed while the panel is open, so hidden rows can't be tapped.)
                if (showResults) {
                    SearchResults(
                        state = results,
                        onPlay = { track ->
                            (results as? ResultsState.Done)?.let { done ->
                                onPlay(done.tracks, done.tracks.indexOf(track), null)
                            }
                        },
                        onRetry = { attempt++ },
                        onQueue = onQueue,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxSize()
                            .padding(
                                top = (barTop + barHeight + 12f).u,
                                start = 80.u,
                                end = 80.u,
                                bottom = dockInset.u, // stop above the mini player
                            ),
                    )
                }

                // Suggestions line up with the text inside the bar.
                if (open && listItems.isNotEmpty()) SearchSuggestions(
                    query = query,
                    items = listItems,
                    onTapEmptySpace = ::tapOutside,
                    // Scrolling the list puts the keyboard away so more of it shows.
                    onUserScroll = { keyboard = false },
                    onPick = ::submit,
                    onRemove = { recent = history.remove(it) },
                    // Rows blur as they pass behind the keyboard glass.
                    glassTopPx = { screenHeightPx - keyboardHeightPx * kb },
                    // Lets the last rows scroll up clear of the keyboard (or the mini player).
                    bottomPadding = (maxOf(KEYBOARD_HEIGHT * kb, dockInset * (1f - kb)) + 24f).u,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxSize()
                        .padding(
                            top = (barTop + barHeight + lerp(40f, 24f)).u,
                            // Icons sit under the bar's magnifier, text under the typed text,
                            // × buttons under the mic.
                            start = 126.u,
                            end = 136.u,
                        ),
                )

                if (libP < 1f) SearchBar(
                    query = query,
                    // Blinking cursor while typing, and on the idle screen like the mockup.
                    active = keyboardShown || !searching,
                    onActivate = ::activate,
                    onClear = { query = ""; activate() },
                    onVoiceSearch = onVoiceSearch,
                    compact = progress,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = barTop.u, start = 96.u, end = 96.u)
                        .graphicsLayer { alpha = 1f - libP },
                )

                // Glass keyboard slides up from the bottom edge.
                if (kb > 0f) {
                    GlassKeyboard(
                        onType = { c ->
                            // No leading spaces or double spaces.
                            if (c != ' ' || (query.isNotEmpty() && !query.endsWith(' '))) query += c
                        },
                        onBackspace = { if (query.isNotEmpty()) query = query.dropLast(1) },
                        onSearch = { if (query.isNotBlank()) submit(query.trim()) },
                        onHide = { keyboard = false },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer {
                                translationY = (1f - kb) * KEYBOARD_HEIGHT.u.toPx()
                                alpha = 0.4f + 0.6f * kb
                            },
                    )
                }
            }
        }
    }
}

@Preview(widthDp = 1000, heightDp = 600, showBackground = true)
@Composable
private fun HomeScreenPreview() {
    HeadunitMusicTheme {
        val context = androidx.compose.ui.platform.LocalContext.current
        HomeScreen(
            youtube = YouTubeSearch(apiKey = ""),
            history = remember { SearchHistory(context) },
            libraries = remember { LibraryStore(context) },
            onPlay = { _, _, _ -> },
            onCreateLibrary = {},
            onVoiceSearch = {},
        )
    }
}
