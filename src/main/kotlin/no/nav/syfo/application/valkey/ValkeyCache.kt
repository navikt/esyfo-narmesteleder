package no.nav.syfo.application.valkey

import io.valkey.DefaultJedisClientConfig
import io.valkey.HostAndPort
import io.valkey.JedisPool
import io.valkey.JedisPoolConfig
import io.valkey.exceptions.JedisConnectionException
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.util.logger
import org.slf4j.event.Level

private enum class CacheAction {
    READ,
    WRITE
}

private val cacheAccessFailed = applicationEvent<CacheAction>(
    name = "cache_access_failed",
    level = Level.WARN,
    message = "Valkey access failed; continuing without cache",
    upstream = "valkey",
    fields = mapOf("action" to { it.name }),
)

class ValkeyCache(
    valkeyEnvironment: ValkeyEnvironment,
) {

    private val logger = logger()
    private val objectMapper = jacksonMapper()

    private val jedisPool = JedisPool(
        JedisPoolConfig(),
        HostAndPort(valkeyEnvironment.host, valkeyEnvironment.port),
        DefaultJedisClientConfig.builder()
            .ssl(valkeyEnvironment.ssl)
            .user(valkeyEnvironment.username)
            .password(valkeyEnvironment.password)
            .build()
    )

    fun <T> get(key: String, type: Class<T>): T? {
        try {
            jedisPool.resource.use { jedis ->
                val json = jedis.get(key)
                return json?.let {
                    objectMapper.readValue(it, type)
                }
            }
        } catch (e: JedisConnectionException) {
            logger.logEvent(cacheAccessFailed, CacheAction.READ, cause = e)
            return null
        }
    }

    fun <T> put(key: String, value: T, ttlSeconds: Long = CACHE_TTL_SECONDS) {
        try {
            jedisPool.resource.use { jedis ->
                val json = objectMapper.writeValueAsString(value)
                jedis.setex(key, ttlSeconds, json)
            }
        } catch (e: JedisConnectionException) {
            logger.logEvent(cacheAccessFailed, CacheAction.WRITE, cause = e)
        }
    }

    companion object {
        const val CACHE_TTL_SECONDS = 3600L
    }
}
