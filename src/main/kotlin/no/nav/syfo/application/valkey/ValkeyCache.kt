package no.nav.syfo.application.valkey

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.valkey.DefaultJedisClientConfig
import io.valkey.HostAndPort
import io.valkey.JedisPool
import io.valkey.JedisPoolConfig
import io.valkey.exceptions.JedisConnectionException
import no.nav.syfo.util.logger

class ValkeyCache(
    valkeyEnvironment: ValkeyEnvironment,
) {

    private val logger = logger()

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
                    jacksonObjectMapper().readValue(it, type)
                }
            }
        } catch (e: JedisConnectionException) {
            logger.warn("Got connection error when fetching from valkey! Continuing without cached value", e)
            return null
        }
    }

    fun <T> put(key: String, value: T, ttlSeconds: Long = CACHE_TTL_SECONDS) {
        try {
            jedisPool.resource.use { jedis ->
                val json = jacksonObjectMapper().writeValueAsString(value)
                jedis.setex(key, ttlSeconds, json)
            }
        } catch (e: JedisConnectionException) {
            logger.warn("Got connection error when storing in valkey! Continue without caching", e)
        }
    }

    companion object {
        const val CACHE_TTL_SECONDS = 3600L
    }
}
