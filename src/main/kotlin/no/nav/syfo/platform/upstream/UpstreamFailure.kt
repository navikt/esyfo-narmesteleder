package no.nav.syfo.platform.upstream

/** Low-cardinality log name for an external system. The client that owns the integration defines its constant. */
@JvmInline
value class UpstreamName(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Upstream name must match ${PATTERN.pattern}" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[a-z][a-z0-9_-]*")
    }
}

enum class UpstreamFailureStage(val logValue: String) {
    TOKEN_EXCHANGE("token_exchange"),
    REQUEST("request"),
    RESPONSE("response"),
}

/** [message] is logged; set it only when the upstream documents that its error messages contain no personal data. */
data class UpstreamError(val code: String, val detail: String? = null, val path: String? = null, val message: String? = null)

data class UpstreamFailure(
    val upstream: UpstreamName,
    val stage: UpstreamFailureStage,
    val status: Int?,
    val cause: Throwable? = null,
    val errors: List<UpstreamError> = emptyList(),
) {
    init {
        require(cause != null || errors.isNotEmpty())
    }

    override fun toString(): String = "UpstreamFailure(upstream=$upstream, stage=$stage, status=$status, cause=${cause?.javaClass?.simpleName}, errorCount=${errors.size})"
}
