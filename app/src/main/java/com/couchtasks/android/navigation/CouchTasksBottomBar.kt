package com.couchtasks.android.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState

@Composable
fun CouchTasksBottomBar(navController: NavController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = route == Routes.AllTasks,
            onClick = { navController.navigateTopLevel(Routes.AllTasks) },
            icon = { Text("✓") },
            label = { Text("All") },
        )
        NavigationBarItem(
            selected = route == Routes.Projects || route == Routes.ProjectTasks,
            onClick = {
                navController.navigateTopLevel(
                    route = Routes.Projects,
                    restoreState = route != Routes.ProjectTasks,
                )
            },
            icon = { Text("☰") },
            label = { Text("Projects") },
        )
        NavigationBarItem(
            selected = route == Routes.Settings,
            onClick = { navController.navigateTopLevel(Routes.Settings) },
            icon = { Text("⚙") },
            label = { Text("Settings") },
        )
    }
}

private fun NavController.navigateTopLevel(route: String, restoreState: Boolean = true) {
    navigate(route) {
        popUpTo(Routes.AllTasks) { saveState = true }
        launchSingleTop = true
        this.restoreState = restoreState
    }
}
