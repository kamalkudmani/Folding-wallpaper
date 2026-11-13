package com.foldmotion.wallpapers.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import com.foldmotion.wallpapers.ui.screens.HomeScreen
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
    const val CREATE = "create"
    const val GENERATING = "generating"
    const val PREVIEW = "preview/{packId}"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val LAB = "lab"

    fun preview(id: String) = "preview/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    Tab(Routes.CREATE, R.string.nav_create, Icons.Filled.Add),
    Tab(Routes.LIBRARY, R.string.nav_library, Icons.AutoMirrored.Filled.List),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun FoldMotionApp(vm: MainViewModel, nav: NavHostController = rememberNavController()) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            when (m) {
                is UiMessage.Text -> snackbar.showSnackbar(if (m.arg == null) context.getString(m.resId) else context.getString(m.resId, m.arg))
            }
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in tabs.map { it.route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    for (tab in tabs) {
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = if (vm.onboardingDone) Routes.HOME else Routes.ONBOARDING) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(vm) {
                        vm.onboardingDone = true
                        nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    }
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        vm,
                        onCreate = {
                            vm.updateForm { it.copy(mode = com.foldmotion.core.scene.GenerationMode.CUSTOM) }
                            nav.navigate(Routes.CREATE)
                        },
                        onSurprise = {
                            vm.startRandom()
                            nav.navigate(Routes.CREATE)
                        },
                        onIdea = { idea -> if (vm.generateIdea(idea.id)) nav.navigate(Routes.GENERATING) },
                        onOpenPack = { nav.navigate(Routes.preview(it)) },
                        onLibrary = { nav.navigate(Routes.LIBRARY) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.CREATE) { CreateScreen(vm, onGenerate = { nav.navigate(Routes.GENERATING) }) }
                composable(Routes.GENERATING) {
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
                composable(Routes.PREVIEW, arguments = listOf(navArgument("packId") { type = NavType.StringType })) { e ->
                    PreviewScreen(vm, e.arguments?.getString("packId") ?: "", onBack = { nav.popBackStack() })
                }
                composable(Routes.LIBRARY) { LibraryScreen(vm, onOpenPack = { nav.navigate(Routes.preview(it)) }) }
                composable(Routes.SETTINGS) { SettingsScreen(vm, onLab = { nav.navigate(Routes.LAB) }) }
                composable(Routes.LAB) { LabScreen(vm) }
            }
        }
    }
}
