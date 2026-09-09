package com.acme.b2b.web

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan

/**
 * Anchors the web slice tests. This module is a library, not a deployable, so it has no
 * @SpringBootApplication of its own — and it should not gain one just to be testable.
 * @WebMvcTest limits auto-configuration to the web layer, so nothing here reaches for a
 * database, and @ComponentScan is needed because @SpringBootConfiguration alone does not
 * scan — that is @SpringBootApplication's third annotation.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
class WebTestApplication
