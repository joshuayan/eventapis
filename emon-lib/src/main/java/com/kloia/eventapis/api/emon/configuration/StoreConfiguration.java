package com.kloia.eventapis.api.emon.configuration;

import com.kloia.eventapis.kafka.KafkaProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties("eventapis")
@Component
@Slf4j
@Data
public class StoreConfiguration {
    private KafkaProperties eventBus;
    private String eventTopicRegex = ".*Event";
}
