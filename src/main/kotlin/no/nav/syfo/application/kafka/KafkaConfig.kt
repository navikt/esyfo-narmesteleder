package no.nav.syfo.application.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.*
import kotlin.reflect.KClass

const val JAVA_KEYSTORE = "JKS"
const val PKCS12 = "PKCS12"
const val SSL = "SSL"

fun commonProperties(env: KafkaEnvironment): Properties {
    val sslConfig = env.sslConfig

    return HashMap<String, String>().apply {
        sslConfig?.let {
            put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SSL)
            put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "") // Disable server host name verification
            put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, JAVA_KEYSTORE)
            put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, PKCS12)
            put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, sslConfig.truststoreLocation)
            put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, sslConfig.credstorePassword)
            put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, sslConfig.keystoreLocation)
            put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, sslConfig.credstorePassword)
            put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, sslConfig.credstorePassword)
        }
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, env.brokerUrl)
        remove(SaslConfigs.SASL_MECHANISM)
        remove(SaslConfigs.SASL_JAAS_CONFIG)
        remove(SaslConfigs.SASL_MECHANISM)
    }.toProperties()
}

fun producerProperties(
    env: KafkaEnvironment,
    valueSerializer: KClass<out Serializer<out Any>>,
    keySerializer: KClass<out Serializer<out Any>> = StringSerializer::class
): Properties {
    val producerProperties = commonProperties(env)

    return producerProperties.apply {
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySerializer.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer.java)
    }
}

fun consumerProperties(
    groupId: String,
    env: KafkaEnvironment,
    valueDeserializer: KClass<out Deserializer<out Any>>,
    keyDeserializer: KClass<out Deserializer<out Any>> = StringDeserializer::class
): Properties {
    val consumerProperties = commonProperties(env)

    return consumerProperties.apply {
        put(CommonClientConfigs.GROUP_ID_CONFIG, groupId)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializer.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer.java)
    }
}

fun avroConsumerProperties(
    groupId: String,
    env: KafkaEnvironment,
): Properties {
    val consumerProperties = commonProperties(env)
    val schemaRegistryEnv = env.schemaRegistry

    return consumerProperties.apply {
        put(CommonClientConfigs.GROUP_ID_CONFIG, groupId)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer::class.java)
        put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryEnv.url)
        put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "true")

        val username = schemaRegistryEnv.username
        val password = schemaRegistryEnv.password
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            put(AbstractKafkaSchemaSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE, "USER_INFO")
            put(AbstractKafkaSchemaSerDeConfig.USER_INFO_CONFIG, "$username:$password")
        }
    }
}

interface KafkaListener {
    fun listen()
    suspend fun stop()
}
