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

data class UpstreamFailure(
    val upstream: UpstreamName,
    val stage: UpstreamFailureStage,
    val status: Int?,
    val cause: Throwable,
) {
    override fun toString(): String = "UpstreamFailure(upstream=$upstream, stage=$stage, status=$status, cause=${cause.javaClass.simpleName})"
}
