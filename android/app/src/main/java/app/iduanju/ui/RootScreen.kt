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
import app.iduanju.ui.category.CategoryScreen
import app.iduanju.ui.components.GlassTabBar
import app.iduanju.ui.detail.DetailScreen
import app.iduanju.ui.home.HomeScreen
import app.iduanju.ui.player.PlayerScreen
import app.iduanju.ui.profile.ProfileScreen
import app.iduanju.ui.search.SearchScreen
import app.iduanju.ui.theme.IdjThemeColors

@Composable
fun RootScreen(initialRoute: String? = null) {
    val nav = rememberNavController()
    LaunchedEffect(initialRoute) { initialRoute?.let { runCatching { nav.navigate(it) } } }
    var tab by remember { mutableIntStateOf(0) }
    val back = nav.currentBackStackEntryAsState().value
    val route = back?.destination?.route ?: "home"
    val atRoot = route in setOf("home", "search", "category", "profile")

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
            composable("home") { HomeScreen(onOpenDetail = { nav.navigate("detail/$it") }, onSearch = { nav.navigate("search") }, onPlay = { nav.navigate("player/$it/0") }, onOpenManga = { nav.navigate("manga/" + android.net.Uri.encode(it)) }) }
            composable("search") { SearchScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
            composable("category") { CategoryScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
            composable("profile") { ProfileScreen(onOpenDetail = { nav.navigate("detail/$it") }) }
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
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 4.dp),
            )
        }
    }
}
