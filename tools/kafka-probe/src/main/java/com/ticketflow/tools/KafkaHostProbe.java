package com.ticketflow.tools;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Verifies the host listener and its advertised metadata using the official Kafka client. */
public final class KafkaHostProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a loopback bootstrap address");
        URI address = URI.create("tcp://" + args[0]);
        if (!"127.0.0.1".equals(address.getHost()) || address.getPort() < 1 || address.getPort() > 65535) {
            throw new IllegalArgumentException("Infrastructure probe only supports IPv4 loopback");
        }
        Properties common = new Properties();
        common.put("bootstrap.servers", args[0]);
        common.put("request.timeout.ms", "5000");
        common.put("default.api.timeout.ms", "15000");
        String topic = "ticketflow.infra.probe.v1";
        TopicPartition partition = new TopicPartition(topic, 0);
        String run = UUID.randomUUID().toString();
        List<String> expected = List.of("host-a:infrastructure-only:" + run + ":one",
                "host-b:infrastructure-only:" + run + ":two");
        try (Admin admin = Admin.create(common)) {
            var nodes = admin.describeCluster().nodes().get(20, TimeUnit.SECONDS);
            if (nodes.size() != 1 || nodes.stream().anyMatch(n -> !n.host().equals("127.0.0.1") || n.port() != address.getPort())) {
                throw new IllegalStateException("Host clients received unreachable advertised broker metadata");
            }
            long offset = admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all()
                    .get(20, TimeUnit.SECONDS).get(partition).offset();
            Properties producerConfig = new Properties();
            producerConfig.putAll(common);
            producerConfig.put("key.serializer", StringSerializer.class.getName());
            producerConfig.put("value.serializer", StringSerializer.class.getName());
            producerConfig.put("acks", "all");
            producerConfig.put("enable.idempotence", "true");
            producerConfig.put("delivery.timeout.ms", "20000");
            producerConfig.put("max.block.ms", "15000");
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig)) {
                for (String record : expected) {
                    int separator = record.indexOf(':');
                    producer.send(new ProducerRecord<>(topic, 0, record.substring(0, separator), record.substring(separator + 1)))
                            .get(25, TimeUnit.SECONDS);
                }
            }
            Properties consumerConfig = new Properties();
            consumerConfig.putAll(common);
            consumerConfig.put("key.deserializer", StringDeserializer.class.getName());
            consumerConfig.put("value.deserializer", StringDeserializer.class.getName());
            consumerConfig.put("group.id", "ticketflow-infra-host-" + run);
            consumerConfig.put("enable.auto.commit", "false");
            consumerConfig.put("isolation.level", "read_committed");
            List<String> actual = new ArrayList<>();
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig)) {
                consumer.assign(List.of(partition));
                consumer.seek(partition, offset);
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (actual.size() < expected.size() && System.nanoTime() < deadline) {
                    consumer.poll(Duration.ofMillis(200)).forEach(r -> actual.add(r.key() + ":" + r.value()));
                }
            }
            if (!actual.equals(expected)) throw new IllegalStateException("Host probe records were not consumed in order");
        }
        System.out.println("OK host listener " + args[0] + ": advertised metadata, acknowledged publication and two consumed records");
    }
}
