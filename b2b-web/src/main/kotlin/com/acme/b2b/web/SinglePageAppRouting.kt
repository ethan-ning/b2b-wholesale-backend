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
 * The list lives in [SpaRoutes] because the security rules need the same one — a path
 * forwarded here but not permitted there, or the reverse, answers 401 on a public page.
 *
 * Off unless the app is packaged with a UI to serve. Running the API alone — which is what
 * happens in development, where Vite serves the app — these routes would answer for
 * index.html that is not there.
 */
@Configuration
@ConditionalOnProperty("app.serve-spa", havingValue = "true")
class SinglePageAppRouting : WebMvcConfigurer {

    override fun addViewControllers(registry: ViewControllerRegistry) {
        SpaRoutes.CLIENT_ROUTES
            .forEach { registry.addViewController(it).setViewName("forward:/index.html") }
    }
}
