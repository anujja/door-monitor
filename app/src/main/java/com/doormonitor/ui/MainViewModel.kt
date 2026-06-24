package com.doormonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doormonitor.data.AppSettings
import com.doormonitor.data.CameraDef
import com.doormonitor.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Compose UI (dashboard + settings). Exposes the current [AppSettings] as a
 * StateFlow and provides suspend-free update entry points that delegate to the repository.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    // Null until the first value is loaded from DataStore. Callers use this to avoid acting on
    // the (blank) default before real settings arrive — e.g. routing to Settings by mistake.
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun upsertCamera(camera: CameraDef) = update { s ->
        val others = s.cameras.filterNot { it.id == camera.id }
        s.copy(cameras = others + camera)
    }

    fun removeCamera(id: String) = update { s ->
        s.copy(cameras = s.cameras.filterNot { it.id == id })
    }
}
