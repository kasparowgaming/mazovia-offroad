package pl.mazovia.offroad.ui.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.mazovia.offroad.data.repository.RouteRepository
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.ui.RiderMessages

class SavedRoutesViewModel(private val repository: RouteRepository) : ViewModel() {
    val routes = repository.getAllRoutes()
    private val _deleteId = MutableStateFlow<String?>(null)
    val deleteId = _deleteId.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun requestDelete(id: String) { if (!_busy.value) _deleteId.value = id }
    fun cancelDelete() { if (!_busy.value) _deleteId.value = null }
    fun confirmDelete() {
        val id = _deleteId.value ?: return
        runAction(RiderMessages.DELETE) {
            repository.deleteRoute(requireNotNull(repository.getRouteById(id)))
            _deleteId.value = null
        }
    }
    fun open(id: String, onOpened: (Route) -> Unit) = runAction(RiderMessages.SAVED_ROUTE) {
        onOpened(repository.openRoute(id))
    }
    private fun runAction(message: String, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { action() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("SavedRoutes", "Saved route action failed", e)
                _error.value = message
            } finally { _busy.value = false }
        }
    }
}
