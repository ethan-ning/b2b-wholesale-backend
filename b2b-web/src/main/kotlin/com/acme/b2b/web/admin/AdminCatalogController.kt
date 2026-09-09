package com.acme.b2b.web.admin

import com.acme.b2b.application.admin.*
import com.acme.b2b.application.admin.dto.*
import com.acme.b2b.application.catalog.dto.PagedDTO
import com.acme.b2b.application.catalog.dto.ProductDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Catalog management. Paths and payloads match the portal's api/adminApi.ts.
 *
 * There is no create endpoint: products originate in the ERP and arrive by sync. An
 * admin edits the portal-owned fields of one that already exists.
 */
@RestController
@RequestMapping("/api/admin")
class AdminCatalogController(
    private val products: ProductAdminService,
    private val categories: CategoryAdminService,
    private val inventory: InventoryQueryService,
    private val dashboard: DashboardService,
) {

    @GetMapping("/dashboard")
    fun dashboard(): DashboardStatsDTO = dashboard.stats()

    // ─── Products ────────────────────────────────────────────────────────
    @GetMapping("/products")
    fun listProducts(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ): PagedDTO<ProductDTO> = products.list(AdminProductQuery(search, status, page, size))

    @GetMapping("/products/{id}")
    fun productById(@PathVariable id: Long): ResponseEntity<AdminProductDTO> =
        products.findById(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PutMapping("/products/{id}")
    fun updateProduct(@PathVariable id: Long, @RequestBody command: UpdateProductCommand): AdminProductDTO =
        products.update(id, command)

    @DeleteMapping("/products/{id}")
    fun deleteProduct(@PathVariable id: Long): ResponseEntity<Void> {
        products.delete(id)
        return ResponseEntity.noContent().build()
    }

    // ─── Categories ──────────────────────────────────────────────────────
    @GetMapping("/categories")
    fun categoryTree(): List<CategoryNodeDTO> = categories.tree()

    @PostMapping("/categories")
    fun createCategory(@RequestBody command: CreateCategoryCommand): ResponseEntity<CategoryNodeDTO> =
        ResponseEntity.status(201).body(categories.create(command))

    @PutMapping("/categories/{id}")
    fun renameCategory(@PathVariable id: Long, @RequestBody command: RenameCategoryCommand): CategoryNodeDTO =
        categories.rename(id, command)

    @DeleteMapping("/categories/{id}")
    fun deleteCategory(@PathVariable id: Long): ResponseEntity<Void> {
        categories.delete(id)
        return ResponseEntity.noContent().build()
    }

    // ─── Inventory (read-only; the ERP owns stock) ───────────────────────
    @GetMapping("/inventory")
    fun inventory(
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "false") lowStock: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PagedDTO<SkuStockDTO> = inventory.list(StockQuery(search, lowStock, page, size))
}
