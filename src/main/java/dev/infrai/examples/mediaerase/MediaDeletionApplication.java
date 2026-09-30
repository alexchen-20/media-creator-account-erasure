package dev.infrai.examples.mediaerase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MediaDeletionApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaDeletionApplication.class, args);
    }
}
