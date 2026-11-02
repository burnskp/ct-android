package com.couchtasks.android.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.couchtasks.android.R

@Composable
fun CouchTasksBottomBar(navController: NavController, reviewEnabled: Boolean, dueEnabled: Boolean) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = route == Routes.AllTasks,
            onClick = { navController.navigateTopLevel(Routes.AllTasks) },
            icon = { NavIcon(R.drawable.ic_nav_all, "All") },
        )
        NavigationBarItem(
            selected = route == Routes.Projects || route == Routes.ProjectTasks,
            onClick = {
                navController.navigateTopLevel(
                    route = Routes.Projects,
                    restoreState = route != Routes.ProjectTasks,
                )
            },
            icon = { NavIcon(R.drawable.ic_nav_projects, "Projects") },
        )
        NavigationBarItem(
            selected = route == Routes.Due,
            onClick = { navController.navigateTopLevel(Routes.Due) },
            enabled = dueEnabled,
            icon = { NavIcon(R.drawable.ic_nav_due, "Due") },
            modifier = Modifier.testTag("DueTab"),
        )
        NavigationBarItem(
            selected = route == Routes.Review,
            onClick = { navController.navigateTopLevel(Routes.Review) },
            enabled = reviewEnabled,
            icon = { NavIcon(R.drawable.ic_nav_review, "Review") },
            modifier = Modifier.testTag("ReviewTab"),
        )
        NavigationBarItem(
            selected = route == Routes.Settings,
            onClick = { navController.navigateTopLevel(Routes.Settings) },
            icon = { NavIcon(R.drawable.ic_nav_settings, "Settings") },
        )
    }
}

@Composable
private fun NavIcon(@DrawableRes icon: Int, description: String) {
    Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(28.dp))
}

private fun NavController.navigateTopLevel(route: String, restoreState: Boolean = true) {
    navigate(route) {
        popUpTo(Routes.AllTasks) { saveState = true }
        launchSingleTop = true
        this.restoreState = restoreState
    }
}
