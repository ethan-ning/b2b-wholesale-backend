package com.acme.b2b.web

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Hands the browser index.html for paths only the client-side router knows about.
 *
 * A dealer who refreshes on /products/PL-1, or opens a link to it, asks this server for a
 * path no controller serves. Without this they get a 404 for a page that exists — the
 * router would have drawn it, had it been given the chance to load.
 *
 * Only the two path shapes the app actually uses, rather than a catch-all: a genuine typo
 * under /api should still be a 404 rather than a page, and so should a missing asset.
 *
 * Off unless the app is packaged with a UI to serve. Running the API alone — which is what
 * happens in development, where Vite serves the app — these routes would answer for
 * index.html that is not there.
 */
@Configuration
@ConditionalOnProperty("app.serve-spa", havingValue = "true")
class SinglePageAppRouting : WebMvcConfigurer {

    override fun addViewControllers(registry: ViewControllerRegistry) {
        listOf("/admin/**", "/products/**", "/search", "/login", "/change-password")
            .forEach { registry.addViewController(it).setViewName("forward:/index.html") }
    }
}
