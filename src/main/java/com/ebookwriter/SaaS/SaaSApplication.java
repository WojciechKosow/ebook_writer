package com.ebookwriter.SaaS;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.GoogleOAuthProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.config.properties.PostmarkProperties;
import com.ebookwriter.SaaS.config.properties.R2Properties;
import com.ebookwriter.SaaS.config.properties.StripeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties({
		PostmarkProperties.class,
		AnthropicProperties.class,
		StripeProperties.class,
		CreditProperties.class,
		R2Properties.class,
		OpenAiProperties.class,
		GoogleOAuthProperties.class
})
public class SaaSApplication {

	public static void main(String[] args) {
		SpringApplication.run(SaaSApplication.class, args);
	}

}
