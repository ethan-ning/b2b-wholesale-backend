package com.acme.b2b.web.admin

import com.acme.b2b.application.admin.ImageAdminService
import com.acme.b2b.application.admin.dto.ImageDTO
import com.acme.b2b.application.admin.dto.ImageUsageDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/**
 * The image library, and each product's gallery.
 *
 * Upload and attach are separate calls because they are separate ideas: a file joins the
 * library once and may then appear in several products.
 */
@RestController
@RequestMapping("/api/admin")
class AdminImageController(
    private val images: ImageAdminService,
) {

    @GetMapping("/images")
    fun library(): List<ImageUsageDTO> = images.library()

    @PostMapping("/images")
    fun upload(@RequestParam("file") file: MultipartFile): ResponseEntity<ImageDTO> =
        ResponseEntity.status(201).body(images.upload(file.bytes, file.contentType, file.originalFilename))

    @DeleteMapping("/images/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        images.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/products/{productId}/images/{imageId}")
    fun attach(@PathVariable productId: Long, @PathVariable imageId: Long): ResponseEntity<Void> {
        images.attach(productId, imageId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/products/{productId}/images/{imageId}")
    fun detach(@PathVariable productId: Long, @PathVariable imageId: Long): ResponseEntity<Void> {
        images.detach(productId, imageId)
        return ResponseEntity.noContent().build()
    }

    /** The whole order at once: a gallery is arranged, not nudged one place at a time. */
    @PutMapping("/products/{productId}/images/order")
    fun reorder(@PathVariable productId: Long, @RequestBody imageIds: List<Long>): ResponseEntity<Void> {
        images.reorder(productId, imageIds)
        return ResponseEntity.noContent().build()
    }

    /** A null body clears it. */
    @PutMapping("/products/{productId}/variants/{variantId}/main-image")
    fun setMainImage(
        @PathVariable productId: Long,
        @PathVariable variantId: Long,
        @RequestBody(required = false) body: MainImageRequest?,
    ): ResponseEntity<Void> {
        images.setMainImage(productId, variantId, body?.imageId)
        return ResponseEntity.noContent().build()
    }
}

data class MainImageRequest(val imageId: Long?)
