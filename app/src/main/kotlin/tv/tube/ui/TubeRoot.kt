package tv.tube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.tube.ui.guide.GuideScreen
import tv.tube.ui.intro.TubePowerOff
import tv.tube.ui.intro.TubePowerOn
import tv.tube.ui.library.DetailsScreen
import tv.tube.ui.library.LibraryScreen
import tv.tube.ui.library.VodPlayerScreen
import tv.tube.ui.live.LiveScreen
import tv.tube.ui.search.SearchScreen
import tv.tube.ui.settings.ProviderScreen
import tv.tube.ui.settings.SettingsScreen
import tv.tube.ui.theme.Tube

@UnstableApi
@Composable
fun TubeRoot(vm: TubeViewModel, onQuit: () -> Unit) {
    val providers by vm.providerList.collectAsState()
    val introEnabled by vm.introEnabled.collectAsState()
    var introDone by rememberSaveable { mutableStateOf(false) }
    var quitting by remember { mutableStateOf(false) }
    val toast by vm.toast.collectAsState()

    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Tube.ground)) {
            TubeNav(vm, startAtProviders = providers.isEmpty(), onQuit = { quitting = true })
            if (toast != null) {
                LaunchedEffect(toast) { delay(5000); vm.clearToast() }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).background(Tube.surface2).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(toast!!, style = Tube.body.copy(color = Tube.text))
                }
            }
        }
    }

    // Design-unit density: the mockups are laid out on a 1920 px canvas at 1.333 px per unit, so
    // 1 dp here is 1/1440 of the screen width on any panel (1080p, 720p sticks, 4K).
    val cfg = LocalConfiguration.current
    val base = LocalDensity.current
    val widthPx = cfg.screenWidthDp * base.density
    val scaled = remember(widthPx) { Density(density = widthPx / 1440f, fontScale = 1f) }
    CompositionLocalProvider(LocalDensity provides scaled) {
        when {
            quitting -> TubePowerOff(onFinished = onQuit) { body() }
            !introDone && introEnabled -> TubePowerOn(onFinished = { introDone = true }) { body() }
            else -> body()
        }
    }
}

@UnstableApi
@Composable
private fun TubeNav(vm: TubeViewModel, startAtProviders: Boolean, onQuit: () -> Unit) {
    val nav = rememberNavController()
    val go: (String) -> Unit = { route ->
        when (route) {
            "live" -> nav.navigate("live") { popUpTo("live") { inclusive = true } }
            else -> nav.navigate(route) { launchSingleTop = true; popUpTo("live") }
        }
    }
    NavHost(nav, startDestination = if (startAtProviders) "provider/new" else "live") {
        composable("live") { LiveScreen(vm, go = go, onQuit = onQuit) }
        composable("guide") { GuideScreen(vm, go = go, onBack = { nav.popBackStack() }) }
        composable("movies") { LibraryScreen(vm, kind = "vod", go = go, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("details/vod/$id") }) }
        composable("series") { LibraryScreen(vm, kind = "series", go = go, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("details/series/$id") }) }
        composable("details/{kind}/{id}", arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.LongType })) { e ->
            DetailsScreen(vm, kind = e.arguments!!.getString("kind")!!, id = e.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onPlay = { nav.navigate("vodplayer") })
        }
        composable("vodplayer") { VodPlayerScreen(vm, onBack = { nav.popBackStack() }) }
        composable("search") { SearchScreen(vm, go = go, onBack = { nav.popBackStack() }, onOpen = { kind, id -> nav.navigate("details/$kind/$id") }) }
        composable("settings") { SettingsScreen(vm, go = go, onBack = { nav.popBackStack() }, onEditProvider = { id -> nav.navigate("provider/$id") }) }
        composable("provider/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            val id = e.arguments!!.getString("id")!!
            ProviderScreen(
                vm,
                providerId = if (id == "new") null else id,
                onDone = { nav.navigate("live") { popUpTo(0) { inclusive = true } } },
                onBack = { if (!nav.popBackStack()) nav.navigate("live") { popUpTo(0) { inclusive = true } } },
            )
        }
    }
}
