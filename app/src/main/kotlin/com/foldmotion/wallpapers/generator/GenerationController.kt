package com.foldmotion.wallpapers.generator

import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.pack.ValidationCode
import com.foldmotion.core.scene.GenerationRequest
import com.foldmotion.core.scene.JobStatus
import com.foldmotion.core.scene.PipelineStage
import com.foldmotion.core.scene.PromptInterpretation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GenerationJob(
    val request: GenerationRequest,
    val profileId: String,
    val status: JobStatus = JobStatus.QUEUED,
    val stage: PipelineStage? = null,
    val completed: List<PipelineStage> = emptyList(),
    val packId: String? = null,
    val interpretation: PromptInterpretation? = null,
    val error: GenerationErrorCode? = null,
    val validationCodes: List<ValidationCode> = emptyList(),
    val durationMs: Long = 0,
) {
    val isRunning: Boolean get() = status in RUNNING
    val isFinished: Boolean get() = !isRunning

    private companion object {
        val RUNNING = setOf(JobStatus.QUEUED, JobStatus.GENERATING, JobStatus.COMPOSING, JobStatus.VALIDATING, JobStatus.CANCEL_REQUESTED)
    }
}

/**
 * Runs at most one generation at a time and exposes its progress as state.
 *
 * Idempotent by request key (blueprint section 08): pressing Generate twice, or a recomposition
 * re-sending the same request, never starts a second job.
 */
class GenerationController(
    private val provider: GenerationProvider,
    private val scope: CoroutineScope,
) {
    private val _job = MutableStateFlow<GenerationJob?>(null)
    val job: StateFlow<GenerationJob?> = _job.asStateFlow()
    private var running: Job? = null

    fun start(request: GenerationRequest, profile: DeviceProfile) {
        val current = _job.value
        if (current != null && current.request.idempotencyKey == request.idempotencyKey) return
        if (current?.isRunning == true) return
        _job.value = GenerationJob(request, profile.id)
        running = scope.launch {
            try {
                val outcome = provider.generate(request, profile) { stage ->
                    _job.update { j ->
                        if (j == null || j.request.idempotencyKey != request.idempotencyKey || j.status == JobStatus.CANCEL_REQUESTED) j
                        else j.copy(status = stage.status, stage = stage, completed = j.completed + listOfNotNull(j.stage))
                    }
                }
                _job.update {
                    it?.copy(
                        status = JobStatus.READY, stage = null,
                        completed = PipelineStage.entries.toList(),
                        packId = outcome.pack.id, interpretation = outcome.interpretation, durationMs = outcome.durationMs,
                    )
                }
            } catch (e: CancellationException) {
                _job.update { it?.copy(status = JobStatus.CANCELLED, stage = null) }
            } catch (e: GenerationException) {
                _job.update { it?.copy(status = JobStatus.FAILED, stage = null, error = e.code, validationCodes = e.validationCodes) }
            } catch (e: Exception) {
                _job.update { it?.copy(status = JobStatus.FAILED, stage = null, error = GenerationErrorCode.RENDER_FAILED) }
            }
        }
    }

    fun cancel() {
        val j = _job.value ?: return
        if (!j.isRunning) return
        _job.update { it?.copy(status = JobStatus.CANCEL_REQUESTED) }
        running?.cancel()
    }

    /** Forget a finished job so the next request starts clean. */
    fun dismiss() {
        if (_job.value?.isRunning == true) return
        _job.value = null
    }
}
