package com.feanor91.treadmill.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.feanor91.treadmill.AppContainer
import com.feanor91.treadmill.TreadmillApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as TreadmillApp).container

/** Fabrique de ViewModel branchée sur le AppContainer (injection manuelle, sans framework). */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline build: (AppContainer) -> VM,
): VM {
    val container = appContainer()
    return viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = build(container) as T
        },
    )
}
