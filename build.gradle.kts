plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
}

group = "ar.melocoton"
version = "0.0.1-SNAPSHOT"
description = "Melocoton REST API"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// Authentication: Spring Security validates and issues the session JWT (DI-06, DI-28)
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	// Argon2id password hashing needs Bouncy Castle (DI-07)
	implementation("org.bouncycastle:bcprov-jdk18on:1.81")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	// OpenAPI contract and Swagger UI (DI-11)
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
	// springdoc reads Kotlin nullability through Jackson 2 to mark non-null fields as required
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	// Architecture rules: dependencies point to the domain (DI-18)
	testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
	// Mocks for use case tests that check calls to ports (DI-35)
	testImplementation("io.mockk:mockk:1.14.12")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

// Local runs load the sample accounts (DevSeed, application-dev.yml).
tasks.bootRun {
	systemProperty("spring.profiles.active", "dev")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Every test runs against melocoton_test (application-test.yml).
	systemProperty("spring.profiles.active", "test")
}

// Regenerates openapi/openapi.json from the API (DI-11). Review the diff and commit it.
tasks.register<Test>("updateOpenApi") {
	description = "Regenerates openapi/openapi.json from the running API."
	group = "documentation"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	systemProperty("openapi.update", "true")
	filter { includeTestsMatching("*OpenApiContractTest") }
	outputs.upToDateWhen { false }
}
