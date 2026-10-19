package com.feanor91.treadmill.ui.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feanor91.treadmill.AppContainer
import com.feanor91.treadmill.data.PlanEntity
import com.feanor91.treadmill.data.PlanWithSteps
import com.feanor91.treadmill.data.ScheduleEntity
import com.feanor91.treadmill.data.ScheduledItem
import com.feanor91.treadmill.data.StepEntity
import com.feanor91.treadmill.domain.MachineLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Collections

class PlannerViewModel(private val c: AppContainer) : ViewModel() {
    val plans: StateFlow<List<PlanWithSteps>> =
        c.repository.plans.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val upcoming: StateFlow<List<ScheduledItem>> =
        c.repository.upcoming().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun schedule(planId: Long, date: LocalDate, weeks: Int) = viewModelScope.launch {
        c.repository.schedule(planId, date, weeks)
    }

    fun unschedule(item: ScheduleEntity) = viewModelScope.launch { c.repository.unschedule(item) }
    fun deletePlan(id: Long) = viewModelScope.launch { c.repository.deletePlan(id) }
}

/** Palier en cours d'édition (état UI, converti en StepEntity à l'enregistrement). */
data class DraftStep(
    val durationSec: Int = 300,
    val speedKmh: Double = 6.0,
    val inclinePct: Double = 0.0,
    val label: String = "",
)

data class PlanDraft(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val steps: List<DraftStep> = listOf(DraftStep(label = "Échauffement")),
)

class PlanEditorViewModel(private val c: AppContainer, private val planId: Long) : ViewModel() {
    val draft = MutableStateFlow(PlanDraft())
    val limits: StateFlow<MachineLimits> = c.ftms.limits
    val saved = MutableStateFlow(false)

    init {
        if (planId != 0L) viewModelScope.launch {
            c.repository.plan(planId)?.let { p ->
                draft.value = PlanDraft(
                    p.plan.id, p.plan.name, p.plan.description,
                    p.orderedSteps.map { DraftStep(it.durationSec, it.speedKmh, it.inclinePct, it.label) },
                )
            }
        }
    }

    fun setName(v: String) = draft.update { it.copy(name = v) }
    fun setDescription(v: String) = draft.update { it.copy(description = v) }

    fun addStep() = draft.update { d ->
        val last = d.steps.lastOrNull() ?: DraftStep()
        d.copy(steps = d.steps + last.copy(label = ""))
    }

    fun removeStep(i: Int) = draft.update { d -> d.copy(steps = d.steps.filterIndexed { idx, _ -> idx != i }) }
    fun duplicateStep(i: Int) = draft.update { d ->
        d.copy(steps = d.steps.toMutableList().also { it.add(i + 1, it[i]) })
    }

    fun moveStep(i: Int, delta: Int) = draft.update { d ->
        val j = i + delta
        if (j !in d.steps.indices) d else d.copy(steps = d.steps.toMutableList().also { Collections.swap(it, i, j) })
    }

    fun updateStep(i: Int, f: (DraftStep) -> DraftStep) = draft.update { d ->
        d.copy(steps = d.steps.mapIndexed { idx, s -> if (idx == i) f(s) else s })
    }

    fun save() {
        val d = draft.value
        if (d.name.isBlank() || d.steps.isEmpty()) return
        viewModelScope.launch {
            c.repository.savePlan(
                PlanEntity(d.id, d.name.trim(), d.description.trim()),
                d.steps.mapIndexed { i, s ->
                    StepEntity(planId = d.id, position = i, durationSec = s.durationSec, speedKmh = s.speedKmh, inclinePct = s.inclinePct, label = s.label)
                },
            )
            saved.value = true
        }
    }
}
