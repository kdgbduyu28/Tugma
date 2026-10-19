package com.timborja.tugma.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.timborja.tugma.AppContainer
import com.timborja.tugma.TugmaApp
import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.core.ConfirmedPlan
import kotlinx.coroutines.launch

/** Shared plan → confirm → execute flow. Screens show [pending] in a [ConfirmPlanDialog]. */
abstract class PlanViewModel(protected val c: AppContainer) : ViewModel() {
    val household get() = c.sonos.state
    val demo get() = c.settings.demoMode

    var pending by mutableStateOf<CommandPlan?>(null)
        protected set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var succeeded by mutableStateOf(false)
        private set

    fun dismissPlan() {
        pending = null
    }

    fun execute(confirmed: ConfirmedPlan) {
        pending = null
        busy = true
        viewModelScope.launch {
            val result = c.sonos.execute(confirmed)
            busy = false
            result.onSuccess {
                succeeded = true
                message = "Done: ${confirmed.plan.title}"
                onExecuted()
            }.onFailure { message = it.message ?: "Failed" }
        }
    }

    protected open fun onExecuted() {}

    fun refresh() {
        viewModelScope.launch { c.sonos.refresh() }
    }
}

@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as TugmaApp
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app.container) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    message: String? = null,
    onMessageShown: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = actions,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier,
        content = content,
    )
}
