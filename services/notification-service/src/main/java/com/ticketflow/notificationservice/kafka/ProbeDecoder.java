package com.ticketflow.notificationservice.kafka;
import com.ticketflow.notificationservice.domain.NotificationCommand;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
@Component
public class ProbeDecoder {
    private final JsonMapper mapper;
    public ProbeDecoder(JsonMapper mapper){this.mapper=mapper;}
    public NotificationCommand decode(String raw){
        if(raw==null||raw.length()>8192)throw new IllegalArgumentException("INVALID_PROBE");
        try{
            var root=mapper.readTree(raw);var data=root.path("data");
            if(!root.isObject()||!"NotificationProbe".equals(root.path("eventType").asText())||!root.path("schemaVersion").isIntegralNumber()||!root.path("schemaVersion").canConvertToInt()||root.path("schemaVersion").asInt()!=1
                ||!"infra-test".equals(root.path("source").asText())||!data.path("bookingId").isIntegralNumber()||!data.path("customerId").isIntegralNumber()
                ||!data.path("bookingId").canConvertToLong()||!data.path("customerId").canConvertToLong())throw new IllegalArgumentException();
            return new NotificationCommand(UUID.fromString(root.path("eventId").asText()),data.path("bookingId").asLong(),data.path("customerId").asLong(),
                NotificationCommand.Kind.valueOf(data.path("kind").asText()),Instant.parse(root.path("occurredAt").asText()));
        }catch(Exception invalid){throw new IllegalArgumentException("INVALID_PROBE");}
    }
}
