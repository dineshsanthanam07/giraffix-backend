package com.giraffix;

import com.giraffix.config.ClaudeProperties;
import com.giraffix.config.S3Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ClaudeProperties.class, S3Properties.class})
public class GiraffixBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(GiraffixBackendApplication.class, args);
	}

}
