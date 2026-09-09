package com.acme.b2b.web.catalog

import com.acme.b2b.application.catalog.CatalogQueryService
import com.acme.b2b.application.catalog.ProductQuery
import com.acme.b2b.application.catalog.dto.CategoryDTO
import com.acme.b2b.application.catalog.dto.PagedDTO
import com.acme.b2b.application.catalog.dto.ProductDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

/**
 * Dealer-facing catalog. Paths and payload shapes match the portal's api/catalog.ts, so
 * the frontend can be pointed here by changing VITE_API_BASE_URL alone.
 *
 * The controller only adapts HTTP to a use case — no pricing, no filtering logic.
 */
@RestController
@RequestMapping("/api")
class CatalogController(
    private val catalog: CatalogQueryService,
) {

    @GetMapping("/products")
    fun search(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) category: Long?,
        @RequestParam(required = false) priceMin: BigDecimal?,
        @RequestParam(required = false) priceMax: BigDecimal?,
        @RequestParam(defaultValue = "relevance") sort: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ): PagedDTO<ProductDTO> =
        catalog.search(ProductQuery(search, category, priceMin, priceMax, sort, page, size))

    @GetMapping("/products/{spuCode}")
    fun bySpuCode(@PathVariable spuCode: String): ResponseEntity<ProductDTO> =
        catalog.findBySpuCode(spuCode)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/categories")
    fun categories(): List<CategoryDTO> = catalog.categoryTree()
}
