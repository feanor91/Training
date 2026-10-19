package com.feanor91.treadmill

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.feanor91.treadmill.domain.WorkoutState
import com.feanor91.treadmill.ui.components.appContainer
import com.feanor91.treadmill.ui.history.HistoryScreen
import com.feanor91.treadmill.ui.home.HomeScreen
import com.feanor91.treadmill.ui.planner.PlanEditorScreen
import com.feanor91.treadmill.ui.planner.PlannerScreen
import com.feanor91.treadmill.ui.theme.TreadmillTheme
import com.feanor91.treadmill.ui.workout.WorkoutScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TreadmillTheme { AppNav() } }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Aujourd'hui", Icons.Default.DirectionsRun),
    Planner("planner", "Plans", Icons.Default.EventNote),
    History("history", "Historique", Icons.Default.History),
}

private const val WORKOUT = "workout"
private const val EDITOR = "editor/{planId}"

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val engine = appContainer().engine
    val workoutState by engine.ui.collectAsState()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    // L'écran de séance s'ouvre/se ferme tout seul avec l'état du moteur (y compris au relancement de l'app).
    LaunchedEffect(workoutState.state, route) {
        if (workoutState.state != WorkoutState.Idle && route != null && route != WORKOUT) {
            nav.navigate(WORKOUT) { launchSingleTop = true }
        } else if (workoutState.state == WorkoutState.Idle && route == WORKOUT) {
            nav.popBackStack()
        }
    }

    val showBar = Tab.entries.any { t -> entry?.destination?.hierarchy?.any { it.route == t.route } == true }
    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = entry?.destination?.hierarchy?.any { it.route == t.route } == true,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.padding(bottom = if (showBar) padding.calculateBottomPadding() else 0.dp)) {
            composable(Tab.Home.route) { HomeScreen() }
            composable(Tab.Planner.route) { PlannerScreen(onEditPlan = { nav.navigate("editor/$it") }) }
            composable(Tab.History.route) { HistoryScreen() }
            composable(EDITOR, listOf(navArgument("planId") { type = NavType.LongType })) {
                PlanEditorScreen(it.arguments?.getLong("planId") ?: 0L, onClose = { nav.popBackStack() })
            }
            composable(WORKOUT) { WorkoutScreen() }
        }
    }
}
