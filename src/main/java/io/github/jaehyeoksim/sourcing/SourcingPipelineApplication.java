package io.github.jaehyeoksim.sourcing;

import io.github.jaehyeoksim.sourcing.common.CollectorProperties;
import io.github.jaehyeoksim.sourcing.common.ListingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({CollectorProperties.class, ListingProperties.class})
public class SourcingPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(SourcingPipelineApplication.class, args);
    }
}
