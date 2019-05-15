package com.kotlin.appengine

import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.google.cloud.vision.v1.ImageAnnotatorClient
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class AppEngineApplication {

	@Bean
	fun storage(): Storage = StorageOptions.getDefaultInstance().service

	@Bean
	fun vision(): ImageAnnotatorClient = ImageAnnotatorClient.create()
}

fun main(args: Array<String>) {
	runApplication<AppEngineApplication>(*args)
}
