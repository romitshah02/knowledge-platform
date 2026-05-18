package org.sunbird.kafka.client

import java.util.Properties
import org.apache.kafka.clients.consumer.{Consumer, ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{LongDeserializer, LongSerializer, StringDeserializer, StringSerializer}
import org.sunbird.common.Platform
import org.sunbird.common.exception.ClientException
import org.sunbird.telemetry.logger.TelemetryManager


class KafkaClient {

	private val BOOTSTRAP_SERVERS = Platform.getString("kafka.urls","localhost:9092")
	private val producer = createProducer()
	private val consumer = createConsumer()

	registerShutdownHook()

	protected def getProducer: Producer[Long, String] = producer
	protected def getConsumer: Consumer[Long, String] = consumer

	@throws[Exception]
	def send(event: String, topic: String): Unit = {
		if (!Platform.getBoolean("kafka.topic.send.enable",true)) return
		if (validate(topic))
			getProducer.send(new ProducerRecord[Long, String](topic, event))
		else {
			TelemetryManager.error("Topic with name: " + topic + ", does not exists.")
			throw new ClientException("TOPIC_NOT_FOUND_EXCEPTION", "Topic with name: " + topic + ", does not exists.")
		}
	}

	@throws[Exception]
	def validate(topic: String): Boolean = {
		val topics = getConsumer.listTopics
		topics.keySet.contains(topic)
	}

	/**
	 * Closes the Kafka producer and consumer, flushing any pending messages first.
	 * Safe to call multiple times.
	 */
	def close(): Unit = {
		try {
			if (producer != null) {
				producer.flush()
				producer.close()
			}
		} catch {
			case e: Exception => TelemetryManager.error("Error closing KafkaProducer: " + e.getMessage, e)
		}
		try {
			if (consumer != null) consumer.close()
		} catch {
			case e: Exception => TelemetryManager.error("Error closing KafkaConsumer: " + e.getMessage, e)
		}
	}

	private def registerShutdownHook(): Unit = {
		Runtime.getRuntime.addShutdownHook(new Thread(() => {
			TelemetryManager.log("Shutting down KafkaClient — closing producer and consumer")
			close()
		}))
	}

	private def applySaslConfig(props: Properties): Unit = {
		val saslEnabled = Platform.getBoolean("kafka.sasl.enabled", false)
		if (saslEnabled) {
			val mechanism = Platform.getString("kafka.sasl.mechanism", "PLAIN")
			val protocol = Platform.getString("kafka.security.protocol", "SASL_PLAINTEXT")
			props.put("security.protocol", protocol)
			props.put("sasl.mechanism", mechanism)

			// Option 1: Full JAAS config string (for advanced use cases like SCRAM/GSSAPI)
			val jaasConfig = Platform.getString("kafka.sasl.jaas.config", "")
			if (jaasConfig.nonEmpty) {
				props.put("sasl.jaas.config", jaasConfig)
			} else {
				// Option 2: Build JAAS from simple username/password (no escaping needed in ConfigMap)
				val username = Platform.getString("kafka.sasl.username", "")
				val password = Platform.getString("kafka.sasl.password", "")
				if (username.nonEmpty && password.nonEmpty) {
					val loginModule = mechanism match {
						case "SCRAM-SHA-256" | "SCRAM-SHA-512" =>
							"org.apache.kafka.common.security.scram.ScramLoginModule"
						case _ =>
							"org.apache.kafka.common.security.plain.PlainLoginModule"
					}
					props.put("sasl.jaas.config",
						s"""$loginModule required username="$username" password="$password";""")
				}
			}
		}
	}

	private def createProducer(): KafkaProducer[Long, String] = {
		val props = new Properties()
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS)
		props.put(ProducerConfig.CLIENT_ID_CONFIG, "KafkaClientProducer")
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[LongSerializer].getName)
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
		applySaslConfig(props)
		new KafkaProducer[Long, String](props)
	}

	private def createConsumer(): KafkaConsumer[Long, String] = {
		val props = new Properties()
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS)
		props.put(ConsumerConfig.CLIENT_ID_CONFIG, "KafkaClientConsumer")
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[LongDeserializer].getName)
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
		applySaslConfig(props)
		new KafkaConsumer[Long, String](props)
	}
}
