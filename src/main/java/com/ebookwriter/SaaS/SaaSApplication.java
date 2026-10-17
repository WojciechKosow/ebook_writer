package com.ebookwriter.SaaS;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.config.properties.BlueprintProperties;
import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.GoogleOAuthProperties;
import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.config.properties.KnowledgeWritingProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.config.properties.PostmarkProperties;
import com.ebookwriter.SaaS.config.properties.R2Properties;
import com.ebookwriter.SaaS.config.properties.StripeProperties;
import com.ebookwriter.SaaS.config.properties.StripeConnectProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({
		PostmarkProperties.class,
		AnthropicProperties.class,
		StripeProperties.class,
		CreditProperties.class,
		R2Properties.class,
		OpenAiProperties.class,
		GoogleOAuthProperties.class,
		KnowledgeProperties.class,
		BlueprintProperties.class,
		KnowledgeWritingProperties.class,
		StripeConnectProperties.class
})
public class SaaSApplication {

	public static void main(String[] args) {
		SpringApplication.run(SaaSApplication.class, args);
	}

}
