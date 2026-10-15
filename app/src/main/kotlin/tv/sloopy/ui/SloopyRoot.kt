package tv.sloopy.ui

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
import tv.sloopy.ui.guide.GuideScreen
import tv.sloopy.ui.library.HistoryScreen
import tv.sloopy.ui.intro.SloopyPowerOff
import tv.sloopy.ui.intro.SloopyPowerOn
import tv.sloopy.ui.library.DetailsScreen
import tv.sloopy.ui.library.LibraryScreen
import tv.sloopy.ui.library.VodPlayerScreen
import tv.sloopy.ui.live.LiveScreen
import tv.sloopy.ui.search.SearchScreen
import tv.sloopy.ui.settings.ProviderScreen
import tv.sloopy.ui.settings.SettingsScreen
import tv.sloopy.ui.theme.Sloopy

@UnstableApi
@Composable
fun SloopyRoot(vm: SloopyViewModel, onQuit: () -> Unit) {
    val providers by vm.providerList.collectAsState()
    val introEnabled by vm.introEnabled.collectAsState()
    var introDone by rememberSaveable { mutableStateOf(false) }
    var quitting by remember { mutableStateOf(false) }
    val toast by vm.toast.collectAsState()
    val locked by vm.locked.collectAsState()
    val due by vm.dueReminder.collectAsState()

    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Sloopy.ground)) {
            if (locked) tv.sloopy.ui.pay.UnlockScreen(vm) else SloopyNav(vm, startAtProviders = providers.isEmpty(), onQuit = { quitting = true })
            due?.let { r ->
                tv.sloopy.ui.common.ChoicePrompt(
                    "STARTING NOW", listOf("watch" to "Watch ${r.title.take(26)} on ${r.channelName.take(20)}", "no" to "Not now"),
                    onCancel = { vm.dismissReminder(r, watch = false) }, onPick = { vm.dismissReminder(r, watch = it == "watch") },
                )
            }
            if (toast != null) {
                LaunchedEffect(toast) { delay(5000); vm.clearToast() }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).background(Sloopy.surface2).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(toast!!, style = Sloopy.body.copy(color = Sloopy.text))
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
            quitting -> SloopyPowerOff(onFinished = onQuit) { body() }
            !introDone && introEnabled -> SloopyPowerOn(onFinished = { introDone = true }) { body() }
            else -> body()
        }
    }
}

@UnstableApi
@Composable
private fun SloopyNav(vm: SloopyViewModel, startAtProviders: Boolean, onQuit: () -> Unit) {
    val nav = rememberNavController()
    val go: (String) -> Unit = { route ->
        when (route) {
            "live" -> nav.navigate("live") { popUpTo("live") { inclusive = true } }
            else -> nav.navigate(route) { launchSingleTop = true; popUpTo("live") }
        }
    }
    LaunchedEffect(Unit) {
        vm.homeEvents.collect { nav.navigate("live") { popUpTo("live") { inclusive = true }; launchSingleTop = true } }
    }
    // Hard cuts between screens, no cross-fade: the design language allows motion only for the tube.
    NavHost(
        nav, startDestination = if (startAtProviders) "provider/new" else "live",
        enterTransition = { androidx.compose.animation.EnterTransition.None }, exitTransition = { androidx.compose.animation.ExitTransition.None },
        popEnterTransition = { androidx.compose.animation.EnterTransition.None }, popExitTransition = { androidx.compose.animation.ExitTransition.None },
    ) {
        composable("live") {
            // Opened from search: Back returns to the results. Otherwise Back opens the guide.
            LiveScreen(vm, go = go, onQuit = onQuit, onBack = { if (nav.previousBackStackEntry?.destination?.route == "search") nav.popBackStack() else go("guide") })
        }
        composable("guide") { GuideScreen(vm, go = go, onQuit = onQuit) }
        composable("recordings") { tv.sloopy.ui.record.RecordingsScreen(vm, go = go, onBack = { vm.guideOpensAtSections = true; go("guide") }, onPlay = { nav.navigate("vodplayer") }) }
        composable("history") { HistoryScreen(vm, go = go, onBack = { vm.guideOpensAtSections = true; go("guide") }, onPlay = { nav.navigate("vodplayer") }) }
        composable("movies") { LibraryScreen(vm, kind = "vod", go = go, onBack = { vm.guideOpensAtSections = true; go("guide") }, onOpen = { id -> nav.navigate("details/vod/$id") }) }
        composable("series") { LibraryScreen(vm, kind = "series", go = go, onBack = { vm.guideOpensAtSections = true; go("guide") }, onOpen = { id -> nav.navigate("details/series/$id") }) }
        composable("details/{kind}/{id}", arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.LongType })) { e ->
            DetailsScreen(vm, kind = e.arguments!!.getString("kind")!!, id = e.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onPlay = { nav.navigate("vodplayer") })
        }
        composable("vodplayer") { VodPlayerScreen(vm, onBack = { nav.popBackStack() }) }
        composable("search") { SearchScreen(vm, go = go, onBack = { vm.guideOpensAtSections = true; go("guide") }, onOpen = { kind, id -> nav.navigate("details/$kind/$id") }, onWatch = { nav.navigate("live") { launchSingleTop = true } }) }
        composable("settings") { SettingsScreen(vm, go = go, onBack = { vm.guideOpensAtSections = true; go("guide") }, onEditProvider = { id -> nav.navigate("provider/$id") }) }
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
