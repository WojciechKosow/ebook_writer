package com.ebookwriter.SaaS;

import com.ebookwriter.SaaS.config.properties.PostmarkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
		PostmarkProperties.class
})
public class SaaSApplication {

	public static void main(String[] args) {
		SpringApplication.run(SaaSApplication.class, args);
	}

}
