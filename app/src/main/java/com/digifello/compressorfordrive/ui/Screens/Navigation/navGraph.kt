package com.digifello.compressorfordrive.ui.Screens.Navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.digifello.compressorfordrive.ui.Screens.Home.HomeScreen
import com.digifello.compressorfordrive.ui.Screens.Home.HomeViewModel
import com.digifello.compressorfordrive.ui.Screens.Splash.SplashScreen

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    val vm: HomeViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) { HomeScreen(vm) }
    }
}