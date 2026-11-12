package app.iduanju.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.chrisbanes.haze.hazeSource
import app.iduanju.ui.category.CategoryScreen
import app.iduanju.ui.components.GlassTabBar
import app.iduanju.ui.detail.DetailScreen
import app.iduanju.ui.home.HomeScreen
import app.iduanju.ui.player.PlayerScreen
import app.iduanju.ui.profile.ProfileScreen
import app.iduanju.ui.search.SearchScreen
import app.iduanju.ui.theme.IdjThemeColors

/** Lets deep rows (继续观看 · 全部) open a route without threading callbacks through every page. */
val LocalNavigate = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun RootScreen(initialRoute: String? = null) {
    val nav = rememberNavController()
    LaunchedEffect(initialRoute) { initialRoute?.let { runCatching { nav.navigate(it) } } }
    val updater = app.iduanju.IdjApp.instance.updater
    LaunchedEffect(Unit) { updater.autoCheck() }
    var tab by remember { mutableIntStateOf(0) }
    val back = nav.currentBackStackEntryAsState().value
    val route = back?.destination?.route ?: "home"
    val atRoot = route in setOf("home", "search", "category", "profile")
    /** Backdrop for the floating tab bar's blur (Haze): everything the NavHost draws. */
    val haze = androidx.compose.runtime.remember { dev.chrisbanes.haze.HazeState() }

    Box(Modifier.fillMaxSize()) {
        // "home/<source>" (我的 → Telegram 频道 / 漫画) switches to the 首页 tab and shows that source page.
        val navigate: (String) -> Unit = { r ->
            if (r.startsWith("home/")) {
                app.iduanju.ui.home.HomeJump.label = mapOf("channels" to "频道", "manga" to "漫画")[r.removePrefix("home/")]
                tab = 0
                nav.navigate("home") { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
            } else nav.navigate(r)
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalNavigate provides navigate) {
            NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize().hazeSource(haze)) {
                composable("home") { HomeScreen(onOpenDetail = { nav.navigate("detail/$it") }, onSearch = { nav.navigate("search") }, onPlay = { nav.navigate("player/$it/0") }, onOpenManga = { nav.navigate("manga/" + android.net.Uri.encode(it)) }) }
                composable("search") { SearchScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
                composable("category") { CategoryScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
                composable("library/{kind}") { e -> app.iduanju.ui.library.LibraryListScreen(e.arguments!!.getString("kind")!!, onBack = { nav.popBackStack() }, onOpenDetail = { nav.navigate("detail/$it") }) }
                composable("profile") { ProfileScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
                composable("sources") { app.iduanju.ui.profile.SourcesScreen(onBack = { nav.popBackStack() }) }
                composable("plugins") { app.iduanju.ui.profile.PluginsScreen(onBack = { nav.popBackStack() }) }
                composable("licenses") { app.iduanju.ui.profile.LicensesScreen(onBack = { nav.popBackStack() }) }
                composable("actorRank") { app.iduanju.ui.profile.ActorRankPlaceholder(onBack = { nav.popBackStack() }) }
                composable("board/{genre}/{sub}") { e -> app.iduanju.ui.category.BoardScreen(app.iduanju.ui.home.BoardSpec(app.iduanju.core.hongguo.HGRankGenre.valueOf(e.arguments!!.getString("genre")!!), app.iduanju.core.hongguo.HGRankSub.valueOf(e.arguments!!.getString("sub")!!)), onBack = { nav.popBackStack() }, onOpenDetail = { nav.navigate("detail/$it") }) }
                composable("detail/{sid}", arguments = listOf(navArgument("sid") { type = NavType.StringType })) { e ->
                    val sid = e.arguments!!.getString("sid")!!
                    DetailScreen(
                        seriesId = sid,
                        onBack = { nav.popBackStack() },
                        onPlay = { s, ep -> nav.navigate("player/$s/$ep") },
                        onOpenSeries = { nav.navigate("detail/$it") },
                    )
                }
                composable("manga/{id}") { e -> app.iduanju.manga.MangaDetailScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() },
                        onRead = { id, i, p -> nav.navigate("mangaRead/" + android.net.Uri.encode(id) + "/$i/$p") }) }
                composable("mangaRead/{id}/{i}/{p}", arguments = listOf(navArgument("i") { type = NavType.IntType }, navArgument("p") { type = NavType.IntType })) { e ->
                    app.iduanju.manga.MangaReaderScreen(e.arguments!!.getString("id")!!, e.arguments!!.getInt("i"), e.arguments!!.getInt("p"), onBack = { nav.popBackStack() }) }
                composable(
                    "player/{sid}/{ep}",
                    arguments = listOf(
                        navArgument("sid") { type = NavType.StringType },
                        navArgument("ep") { type = NavType.IntType },
                    ),
                ) { e ->
                    PlayerScreen(
                        seriesId = e.arguments!!.getString("sid")!!,
                        startEp = e.arguments!!.getInt("ep"),
                        onBack = { nav.popBackStack() },
                        onSwitchSeries = { sid -> nav.navigate("player/$sid/0") { popUpTo("player/{sid}/{ep}") { inclusive = true } } },
                    )
                }
            }

        }
        if (atRoot) {
            GlassTabBar(
                selected = tab,
                items = listOf(
                    "首页" to { on -> Icon(Icons.Filled.Home, null, tint = if (on) IdjThemeColors.text else IdjThemeColors.secondary) },
                    "搜索" to { on -> Icon(Icons.Filled.Search, null, tint = if (on) IdjThemeColors.text else IdjThemeColors.secondary) },
                    "找剧" to { on -> Icon(Icons.Filled.GridView, null, tint = if (on) IdjThemeColors.text else IdjThemeColors.secondary) },
                    "我的" to { on -> Icon(Icons.Filled.Person, null, tint = if (on) IdjThemeColors.text else IdjThemeColors.secondary) },
                ),
                onSelect = {
                    tab = it
                    nav.navigate(listOf("home", "search", "category", "profile")[it]) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                haze = haze,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 4.dp),
            )
        }
        app.iduanju.update.UpdateDialog(updater)
    }
}
