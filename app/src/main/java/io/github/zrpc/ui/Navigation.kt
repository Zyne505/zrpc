package io.github.zrpc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.zrpc.AppContainer
import io.github.zrpc.data.AppSettings
import io.github.zrpc.rpc.RpcMode
import io.github.zrpc.ui.about.AboutScreen
import io.github.zrpc.ui.apps.AppsRpcScreen
import io.github.zrpc.ui.console.ConsoleRpcScreen
import io.github.zrpc.ui.custom.CustomRpcScreen
import io.github.zrpc.ui.experimental.ExperimentalRpcScreen
import io.github.zrpc.ui.home.HomeScreen
import io.github.zrpc.ui.login.LoginScreen
import io.github.zrpc.ui.logs.LogsScreen
import io.github.zrpc.ui.media.MediaRpcScreen
import io.github.zrpc.ui.profile.ProfileScreen
import io.github.zrpc.ui.settings.SettingsScreen

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val PROFILE = "profile"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    fun mode(m: RpcMode) = "mode/${m.name}"
}

@Composable
fun ZrpcNavHost(settings: AppSettings) {
    val container = LocalContainer.current
    val nav = rememberNavController()
    val loggedIn by container.vault.hasToken.collectAsState()

    // Token wiped (logout, or Discord rejected it): go back to login.
    LaunchedEffect(loggedIn) {
        if (!loggedIn && nav.currentDestination?.route != Routes.LOGIN) nav.resetTo(Routes.LOGIN)
    }

    NavHost(nav, startDestination = if (loggedIn) Routes.HOME else Routes.LOGIN) {
        composable(Routes.LOGIN) { LoginScreen(onLoggedIn = { nav.resetTo(Routes.HOME) }) }
        composable(Routes.HOME) {
            HomeScreen(
                settings = settings,
                open = { route -> nav.navigate(route) { launchSingleTop = true } },
            )
        }
        composable(Routes.mode(RpcMode.CUSTOM)) { CustomRpcScreen(settings, onBack = { nav.popBackStack() }) }
        composable(Routes.mode(RpcMode.APPS)) { AppsRpcScreen(settings, onBack = { nav.popBackStack() }) }
        composable(Routes.mode(RpcMode.MEDIA)) { MediaRpcScreen(settings, onBack = { nav.popBackStack() }) }
        composable(Routes.mode(RpcMode.CONSOLE)) { ConsoleRpcScreen(settings, onBack = { nav.popBackStack() }) }
        composable(Routes.mode(RpcMode.EXPERIMENTAL)) { ExperimentalRpcScreen(settings, onBack = { nav.popBackStack() }) }
        composable(Routes.PROFILE) { ProfileScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.LOGS) { LogsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(settings, onBack = { nav.popBackStack() }, openAbout = { nav.navigate(Routes.ABOUT) }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}

private fun NavHostController.resetTo(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
