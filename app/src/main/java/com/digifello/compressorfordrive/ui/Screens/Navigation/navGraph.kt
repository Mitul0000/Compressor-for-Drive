package com.digifello.compressorfordrive.ui.Screens.Navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.digifello.compressorfordrive.ui.Screens.Home.HomeScreen
import com.digifello.compressorfordrive.ui.Screens.Home.HomeViewModel
import com.digifello.compressorfordrive.ui.Screens.Splash.SplashScreen
import com.digifello.compressorfordrive.ui.Screens.Test.TestScreen
import com.digifello.compressorfordrive.ui.Screens.Test.TestViewModel

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    // Created here (not per screen) so each tab keeps its state when you switch tabs.
    val homeVm: HomeViewModel = viewModel()
    val testVm: TestViewModel = viewModel()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    Scaffold(
        // Each screen draws its own top bar; only the bottom bar is handled here.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (route == Routes.HOME || route == Routes.TEST) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.HOME,
                        onClick = { navController.openTab(Routes.HOME) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Main") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.TEST,
                        onClick = { navController.openTab(Routes.TEST) },
                        icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                        label = { Text("Test") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SPLASH,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SPLASH) {
                SplashScreen(
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.HOME) { HomeScreen(homeVm) }
            composable(Routes.TEST) { TestScreen(testVm) }
        }
    }
}

private fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}