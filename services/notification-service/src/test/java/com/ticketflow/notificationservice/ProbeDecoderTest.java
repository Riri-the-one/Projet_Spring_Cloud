package com.ticketflow.notificationservice;
import static org.assertj.core.api.Assertions.*;
import com.ticketflow.notificationservice.kafka.ProbeDecoder;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
class ProbeDecoderTest {
    ProbeDecoder decoder=new ProbeDecoder(JsonMapper.builder().build());
    static String probe(UUID id){return "{\"eventId\":\""+id+"\",\"eventType\":\"NotificationProbe\",\"schemaVersion\":1,\"source\":\"infra-test\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"data\":{\"bookingId\":123,\"customerId\":456,\"kind\":\"CONFIRMATION\"}}";}
    @Test void onlyTechnicalProbeIsAccepted(){
        UUID id=UUID.randomUUID();assertThat(decoder.decode(probe(id)).eventId()).isEqualTo(id);
        assertThatThrownBy(()->decoder.decode(probe(id).replace("NotificationProbe","BookingConfirmed"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->decoder.decode(probe(id).replace("infra-test","booking-service"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->decoder.decode("not-json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->decoder.decode(probe(id).replace("\"schemaVersion\":1","\"schemaVersion\":4294967297"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->decoder.decode(probe(id).replace("\"schemaVersion\":1","\"schemaVersion\":1.5"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->decoder.decode("x".repeat(9000))).isInstanceOf(IllegalArgumentException.class);
    }
}
