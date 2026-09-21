package pl.mazovia.offroad.ui.postride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.mazovia.offroad.data.repository.FeedbackRepository
import pl.mazovia.offroad.data.repository.RideRepository
import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.ui.RiderMessages

data class PostRideState(
    val ride: Ride? = null,
    val questions: List<RoadFeedback> = emptyList(),
    val feedbackOpen: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false
)

class PostRideViewModel(private val rides: RideRepository, private val feedback: FeedbackRepository) : ViewModel() {
    private val _state = MutableStateFlow(PostRideState())
    val state = _state.asStateFlow()
    fun load(id: String?) = action(RiderMessages.RIDE) {
        val ride = requireNotNull(id?.let { rides.getRideById(it) })
        _state.value = _state.value.copy(ride = ride.copy(trackPoints = rides.getTrackPoints(ride.id)))
    }
    fun openFeedback() = action(RiderMessages.FEEDBACK) {
        val ride = requireNotNull(_state.value.ride)
        // Normal entry is already persisted by recording. Never create a duplicate ride.
        if (rides.getRideById(ride.id) == null) {
            rides.saveCompletedRide(ride)
        }
        refresh(ride)
        _state.value = _state.value.copy(feedbackOpen = true)
    }
    fun answer(answer: FeedbackAnswer) = action(RiderMessages.FEEDBACK) {
        val question = _state.value.questions.firstOrNull() ?: return@action
        feedback.submitAnswer(question.id, answer)
        refresh(requireNotNull(_state.value.ride))
    }
    fun closeFeedback() { _state.value = _state.value.copy(feedbackOpen = false) }
    private suspend fun refresh(ride: Ride) {
        val pending = feedback.getFeedbackForRide(ride.id).filter { it.answer == null }
            .sortedWith(compareBy<RoadFeedback> { it.timestampMillis }.thenBy { it.id })
        val updated = ride.copy(pendingFeedback = pending.map { it.id })
        rides.updateRide(updated)
        _state.value = _state.value.copy(ride = updated, questions = pending, completed = pending.isEmpty())
    }
    private fun action(message: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("PostRide", "Post-ride operation failed", e)
                _state.value = _state.value.copy(error = message)
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
