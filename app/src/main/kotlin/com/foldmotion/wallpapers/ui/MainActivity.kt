@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.foldmotion.wallpapers.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldmotion.wallpapers.ui.components.LocalNavScope
import com.foldmotion.wallpapers.ui.components.LocalSharedScope
import com.foldmotion.wallpapers.ui.components.PremiumBackground
import com.foldmotion.wallpapers.ui.discover.DiscoverDetailScreen
import com.foldmotion.wallpapers.ui.discover.DiscoverScreen
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import com.foldmotion.wallpapers.ui.components.FloatingNavBar
import com.foldmotion.wallpapers.ui.components.NavItem
import com.foldmotion.wallpapers.ui.theme.FmColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.foldmotion.wallpapers.R
import com.foldmotion.wallpapers.ui.screens.CreateScreen
import com.foldmotion.wallpapers.ui.screens.GeneratingScreen
import com.foldmotion.wallpapers.ui.screens.LabScreen
import com.foldmotion.wallpapers.ui.screens.LibraryScreen
import com.foldmotion.wallpapers.ui.screens.OnboardingScreen
import com.foldmotion.wallpapers.ui.screens.PreviewScreen
import com.foldmotion.wallpapers.ui.screens.SettingsScreen
import com.foldmotion.wallpapers.ui.theme.FoldMotionTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FoldMotionTheme { FoldMotionApp(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Folding changes the window; a profile chosen in Settings may also have changed.
        vm.refreshDevice()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        vm.refreshDevice()
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DISCOVER = "discover/{itemId}"
    const val CREATE = "create"
    const val GENERATING = "generating"
    const val PREVIEW = "preview/{packId}"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val LAB = "lab"

    fun preview(id: String) = "preview/$id"
    fun discover(id: String) = "discover/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    Tab(Routes.CREATE, R.string.nav_create, Icons.Filled.Add),
    Tab(Routes.LIBRARY, R.string.nav_library, Icons.AutoMirrored.Filled.List),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/** A destination whose content can take part in shared-element transitions. */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments = arguments) { entry ->
    CompositionLocalProvider(LocalNavScope provides this) { content(entry) }
}

@Composable
fun FoldMotionApp(vm: MainViewModel, nav: NavHostController = rememberNavController()) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val reduced by vm.reducedMotion.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            when (m) {
                is UiMessage.Text -> snackbar.showSnackbar(if (m.arg == null) context.getString(m.resId) else context.getString(m.resId, m.arg))
            }
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabIndex = tabs.indexOfFirst { it.route == route }

    Box(Modifier.fillMaxSize()) {
        PremiumBackground()
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            SharedTransitionLayout {
                CompositionLocalProvider(LocalSharedScope provides this) {
                NavHost(
                    nav,
                    startDestination = if (vm.onboardingDone) Routes.HOME else Routes.ONBOARDING,
                    enterTransition = { fadeIn(tween(320)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.94f) },
                    exitTransition = { fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 1.03f) },
                    popEnterTransition = { fadeIn(tween(300)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 1.04f) },
                    popExitTransition = { fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 0.94f) },
                ) {
                    screen(Routes.ONBOARDING) {
                        OnboardingScreen(vm) {
                            vm.onboardingDone = true
                            nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                        }
                    }
                    screen(Routes.HOME) {
                        DiscoverScreen(
                            vm,
                            onOpen = { id -> nav.navigate(Routes.discover(id)) },
                            onCreate = {
                                vm.updateForm { it.copy(mode = com.foldmotion.core.scene.GenerationMode.CUSTOM, videoMode = false) }
                                nav.navigate(Routes.CREATE)
                            },
                        )
                    }
                    screen(Routes.DISCOVER, arguments = listOf(navArgument("itemId") { type = NavType.StringType })) { e ->
                        DiscoverDetailScreen(
                            vm, e.arguments?.getString("itemId") ?: "",
                            onBack = { nav.popBackStack() },
                            onPackReady = { id -> nav.navigate(Routes.preview(id)) { popUpTo(Routes.DISCOVER) { inclusive = true } } },
                        )
                    }
                    screen(Routes.CREATE) {
                        CreateScreen(
                            vm,
                            onGenerate = { nav.navigate(Routes.GENERATING) },
                            onOpenPack = { id -> nav.navigate(Routes.preview(id)) },
                        )
                    }
                    screen(Routes.GENERATING) {
                        GeneratingScreen(
                            vm,
                            onReady = { id ->
                                vm.dismissJob()
                                nav.navigate(Routes.preview(id)) { popUpTo(Routes.GENERATING) { inclusive = true } }
                            },
                            onEdit = {
                                vm.dismissJob()
                                nav.navigate(Routes.CREATE) { popUpTo(Routes.GENERATING) { inclusive = true } }
                            },
                            onClose = { nav.popBackStack() },
                        )
                    }
                    screen(Routes.PREVIEW, arguments = listOf(navArgument("packId") { type = NavType.StringType })) { e ->
                        PreviewScreen(vm, e.arguments?.getString("packId") ?: "", onBack = { nav.popBackStack() })
                    }
                    screen(Routes.LIBRARY) { LibraryScreen(vm, onOpenPack = { nav.navigate(Routes.preview(it)) }) }
                    screen(Routes.SETTINGS) { SettingsScreen(vm, onLab = { nav.navigate(Routes.LAB) }) }
                    screen(Routes.LAB) { LabScreen(vm) }
                }
                }
            }
        }

        // Floating glass navigation on every screen after onboarding, so Home is always one tap
        // away (also from a wallpaper just created, which is not one of the four tabs).
        AnimatedVisibility(
            visible = route != null && route != Routes.ONBOARDING,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingNavBar(
                items = tabs.map { NavItem(stringResource(it.label), it.icon) },
                selected = tabIndex,
                onSelect = { i ->
                    // Each tab opens its own screen fresh on top of Home. Saving and restoring
                    // tab stacks would bring back a stale preview or generation screen; the
                    // Create form survives anyway because it lives in the ViewModel.
                    val target = tabs[i].route
                    if (route != target) {
                        nav.navigate(target) {
                            popUpTo(Routes.HOME)
                            launchSingleTop = true
                        }
                    }
                },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .widthIn(max = 560.dp),
            )
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = if (route != Routes.ONBOARDING) 92.dp else 16.dp, start = 16.dp, end = 16.dp),
        ) { data ->
            Snackbar(data, containerColor = FmColors.NightHigh, contentColor = FmColors.Text, shape = RoundedCornerShape(20.dp))
        }
    }
}
