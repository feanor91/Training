package com.feanor91.treadmill.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.feanor91.treadmill.domain.WorkoutStep
import com.feanor91.treadmill.service.WorkoutService

/** Démarre une séance (avec ou sans tapis connecté) et le service qui la garde en vie. */
@Composable
fun rememberWorkoutLauncher(): (name: String, steps: List<WorkoutStep>, scheduleId: Long?) -> Unit {
    val context = LocalContext.current
    val engine = appContainer().engine
    return remember(engine, context) {
        { name, steps, scheduleId ->
            engine.start(name, steps, scheduleId)
            WorkoutService.start(context)
        }
    }
}
