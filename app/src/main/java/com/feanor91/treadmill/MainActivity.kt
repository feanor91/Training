package com.feanor91.treadmill

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
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
import com.feanor91.treadmill.ui.health.HealthScreen
import com.feanor91.treadmill.ui.history.HistoryScreen
import com.feanor91.treadmill.ui.home.HomeScreen
import com.feanor91.treadmill.ui.planner.PlanEditorScreen
import com.feanor91.treadmill.ui.planner.PlannerScreen
import com.feanor91.treadmill.ui.planner.ProgramEditorScreen
import com.feanor91.treadmill.ui.settings.SettingsScreen
import com.feanor91.treadmill.service.WorkoutService
import com.feanor91.treadmill.widget.TodayWidgetProvider
import kotlinx.coroutines.launch
import com.feanor91.treadmill.ui.theme.TreadmillTheme
import com.feanor91.treadmill.ui.workout.WorkoutScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TreadmillTheme { AppNav() } }
        handleWidgetIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    /** Un appui sur le bouton ▶ du widget démarre la séance du jour (ou une course libre). */
    private fun handleWidgetIntent(intent: Intent?) {
        val scheduleId = intent?.getLongExtra(TodayWidgetProvider.EXTRA_START_SCHEDULE_ID, -1L) ?: -1L
        val free = intent?.getBooleanExtra(TodayWidgetProvider.EXTRA_START_FREE, false) == true
        if (scheduleId < 0 && !free) return
        // L'ordre ne doit être exécuté qu'une fois (rotation, retour au premier plan).
        intent?.removeExtra(TodayWidgetProvider.EXTRA_START_SCHEDULE_ID)
        intent?.removeExtra(TodayWidgetProvider.EXTRA_START_FREE)
        val container = (application as TreadmillApp).container
        container.scope.launch {
            if (container.startFromWidget(scheduleId, free)) runCatching { WorkoutService.start(this@MainActivity) }
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Accueil", Icons.Default.DirectionsRun),
    Planner("planner", "Plans", Icons.Default.EventNote),
    Health("health", "Santé", Icons.Default.Favorite),
    History("history", "Historique", Icons.Default.History),
    Settings("settings", "Réglages", Icons.Default.Settings),
}

private const val WORKOUT = "workout"
private const val EDITOR = "editor/{planId}?scheduleId={scheduleId}"
private const val PROGRAM = "program?programId={programId}"

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

    fun goTab(t: Tab) = nav.navigate(t.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    val showBar = Tab.entries.any { t -> entry?.destination?.hierarchy?.any { it.route == t.route } == true }
    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = entry?.destination?.hierarchy?.any { it.route == t.route } == true,
                        onClick = { goTab(t) },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.padding(bottom = if (showBar) padding.calculateBottomPadding() else 0.dp)) {
            composable(Tab.Home.route) { HomeScreen(onOpenSettings = { goTab(Tab.Settings) }) }
            composable(Tab.Planner.route) {
                PlannerScreen(
                    onEditPlan = { nav.navigate("editor/$it") },
                    onEditSession = { planId, scheduleId -> nav.navigate("editor/$planId?scheduleId=$scheduleId") },
                    onNewProgram = { nav.navigate("program") },
                    onEditProgram = { nav.navigate("program?programId=$it") },
                )
            }
            composable(PROGRAM, listOf(navArgument("programId") { type = NavType.LongType; defaultValue = -1L })) {
                ProgramEditorScreen(it.arguments?.getLong("programId") ?: -1L, onClose = { nav.popBackStack() })
            }
            composable(Tab.Health.route) { HealthScreen() }
            composable(Tab.History.route) { HistoryScreen() }
            composable(Tab.Settings.route) { SettingsScreen(onOpenHealth = { goTab(Tab.Health) }) }
            composable(
                EDITOR,
                listOf(
                    navArgument("planId") { type = NavType.LongType },
                    navArgument("scheduleId") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) {
                PlanEditorScreen(
                    it.arguments?.getLong("planId") ?: 0L,
                    it.arguments?.getLong("scheduleId") ?: -1L,
                    onClose = { nav.popBackStack() },
                )
            }
            composable(WORKOUT) { WorkoutScreen() }
        }
    }
}
