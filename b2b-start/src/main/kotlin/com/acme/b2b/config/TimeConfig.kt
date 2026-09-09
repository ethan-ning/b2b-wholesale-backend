package com.acme.b2b.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class TimeConfig {

    /**
     * Injected rather than called statically so a sync run's timestamps can be pinned in
     * a test. UTC because every timestamp in the schema is stored that way; the only
     * place a local zone appears is the cron schedule, which is about when people are
     * asleep rather than about what a row means.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
