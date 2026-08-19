package com.ebookwriter.SaaS;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.config.properties.PostmarkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties({
		PostmarkProperties.class,
		AnthropicProperties.class
})
public class SaaSApplication {

	public static void main(String[] args) {
		SpringApplication.run(SaaSApplication.class, args);
	}

}
