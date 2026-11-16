package com.ticketflow.notificationservice;
import static org.assertj.core.api.Assertions.*;
import com.ticketflow.notificationservice.repo.*;
import com.ticketflow.notificationservice.service.DeliveryWorker;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
@SpringBootTest(properties={"ticketflow.notification.ingress=probe","spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer","spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"})
@ActiveProfiles("postgresql")
class NotificationKafkaIT {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        NotificationPostgresqlProperties.register(p);p.add("spring.kafka.bootstrap-servers",()->System.getenv("NOTIFICATION_KAFKA_BOOTSTRAP"));
        p.add("ticketflow.notification.probe-group",()->"ticketflow-notification-probe-"+System.getenv("NOTIFICATION_TEST_SCHEMA"));
    }
    @Autowired KafkaTemplate<String,String> kafka;@Autowired NotificationRepo notifications;@Autowired ReceiptRepo receipts;@Autowired QuarantineRepo quarantine;@Autowired DeliveryWorker worker;
    void await(java.util.function.BooleanSupplier condition)throws Exception{long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(100);assertThat(condition.getAsBoolean()).isTrue();}
    @Test void realKafkaConsumptionReplayAndQuarantineCommitOnlyAfterPersistence()throws Exception{
        UUID id=UUID.randomUUID();String payload=ProbeDecoderTest.probe(id);String topic="ticketflow.infra.notification.probe.v1";
        kafka.send(topic,id.toString(),payload).get(15,TimeUnit.SECONDS);await(()->notifications.existsById(id));
        worker.deliver(id);worker.deliver(id);worker.deliver(id);assertThat(receipts.findById(id)).isPresent();
        kafka.send(topic,id.toString(),payload).get(15,TimeUnit.SECONDS);
        var marker=kafka.send(topic,id.toString(),payload.replace("\"customerId\":456","\"customerId\":999")).get(15,TimeUnit.SECONDS).getRecordMetadata();
        await(()->quarantine.existsByTopicAndPartitionAndOffset(topic,marker.partition(),marker.offset()));
        assertThat(notifications.findById(id).orElseThrow().getCustomerId()).isEqualTo(456);assertThat(notifications.findById(id).orElseThrow().getAttempts()).isEqualTo(3);
        var bad=kafka.send(topic,"invalid","not-json").get(15,TimeUnit.SECONDS).getRecordMetadata();
        await(()->quarantine.existsByTopicAndPartitionAndOffset(topic,bad.partition(),bad.offset()));
    }
}
