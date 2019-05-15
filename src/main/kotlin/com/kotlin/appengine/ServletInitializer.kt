package com.kotlin.appengine

import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.google.cloud.vision.v1.ImageAnnotatorClient
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer
import org.springframework.context.annotation.Bean

class ServletInitializer: SpringBootServletInitializer() {

	override fun configure(application: SpringApplicationBuilder): SpringApplicationBuilder {
		return application.sources(AppEngineApplication::class.java)
	}
}
