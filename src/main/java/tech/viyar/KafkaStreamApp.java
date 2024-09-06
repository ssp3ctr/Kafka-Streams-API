package tech.viyar;

import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.cdimascio.dotenv.Dotenv;

public class KafkaStreamApp {

    private static final Logger logger = LoggerFactory.getLogger(KafkaStreamApp.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Dotenv dotenv = Dotenv.load();  // Loading environment variables from .env
    private static Producer<String, String> producer;

    public static void main(String[] args) {

        logger.info("Application started");

        // Load configuration from .env
        Properties props = loadPropertiesFromEnv();
        initializeKafkaProducer();  // Initialize Kafka Producer

        // Build Kafka Streams
        StreamsBuilder builder = new StreamsBuilder();

        // Create a stream from the input topic
        KStream<String, String> ordersUpdateStream = builder.stream(
            dotenv.get("INPUT_TOPIC"), 
            org.apache.kafka.streams.kstream.Consumed.with(Serdes.String(), Serdes.String()));  // Ensure the stream has String key and value

        // Process each message and manually send it to the corresponding client topics
        ordersUpdateStream.foreach((key, value) -> {
            try {
                // Log the received message
                logger.info("Received message: {}", value);

                // Extract the array of clients
                JsonNode clients = extractAssignedTo(value);

                // For each client, send the message to a separate topic
                clients.forEach(client -> {
                    String clientId = client.asText();
                    String topicName = "OrdersUpdate" + clientId;

                    // Log the client information
                    logger.info("Processing for client: {}", clientId);

                    // Check and create the topic if it doesn't exist
                    createTopicIfNotExists(topicName, 1, (short) 1);

                    logger.info("Sending message for client {} to topic {}", clientId, topicName);

                    // Manually send the message using Kafka Producer
                    sendMessageToTopic(topicName, key, value);
                });

            } catch (IOException e) {
                logger.error("Error processing message: {}", value, e);
            }
        });

        // Start Kafka Streams
        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();

        // Close streams and producer on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            streams.close();
            producer.close();
        }));
    }

    /**
     * Helper function to extract the array of clients from a JSON string
     * @param json JSON string
     * @return JsonNode containing clients
     */
    private static JsonNode extractAssignedTo(String json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        return node.get("assigned_to");  // Assuming "assigned_to" is now an array
    }

    /**
     * Load Kafka configuration from .env
     * @return Properties object
     */
    private static Properties loadPropertiesFromEnv() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, dotenv.get("APPLICATION_ID"));
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, dotenv.get("KAFKA_BOOTSTRAP_SERVERS"));
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        return props;
    }

    /**
     * Initialize Kafka Producer for manual message sending
     */
    private static void initializeKafkaProducer() {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, dotenv.get("KAFKA_BOOTSTRAP_SERVERS"));
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        producer = new KafkaProducer<>(producerProps);
    }

    /**
     * Manually send message to a specific Kafka topic using Kafka Producer
     * @param topicName Name of the topic
     * @param key Key of the message
     * @param value Value of the message
     */
    private static void sendMessageToTopic(String topicName, String key, String value) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(topicName, key, value);
            RecordMetadata metadata = producer.send(record).get();
            logger.info("Message sent to topic {} with offset {}", topicName, metadata.offset());
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error sending message to topic {}: {}", topicName, e.getMessage());
            Thread.currentThread().interrupt();  // Restore interrupted state
        }
    }

    /**
     * Create a Kafka topic if it doesn't exist
     * @param topicName Name of the topic
     * @param numPartitions Number of partitions
     * @param replicationFactor Replication factor
     */
    private static void createTopicIfNotExists(String topicName, int numPartitions, short replicationFactor) {
        Properties adminProps = new Properties();
        adminProps.put("bootstrap.servers", dotenv.get("KAFKA_BOOTSTRAP_SERVERS"));

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            // Check if the topic already exists
            if (!adminClient.listTopics().names().get().contains(topicName)) {
                logger.info("Topic {} not found, creating...", topicName);

                // Create the topic if it doesn't exist
                NewTopic newTopic = new NewTopic(topicName, numPartitions, replicationFactor);
                adminClient.createTopics(Collections.singletonList(newTopic)).all().get();

                logger.info("Topic {} successfully created.", topicName);
            } else {
                logger.info("Topic {} already exists.", topicName);
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error creating topic {}: {}", topicName, e.getMessage());
            Thread.currentThread().interrupt();  // Restore the interrupted status
        }
    }
}
