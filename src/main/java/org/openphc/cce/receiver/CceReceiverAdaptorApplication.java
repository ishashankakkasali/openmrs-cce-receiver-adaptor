package org.openphc.cce.receiver;

import org.openphc.cce.receiver.config.OpenMrsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({OpenMrsProperties.class})
public class CceReceiverAdaptorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CceReceiverAdaptorApplication.class, args);
    }
}
