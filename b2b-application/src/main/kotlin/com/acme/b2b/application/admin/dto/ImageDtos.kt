package com.acme.b2b.application.admin.dto

/** Published contract for the image library. Matches the portal's api/adminApi.ts. */

data class ImageDTO(
    val id: Long?,
    val url: String,
    val filename: String,
    val contentType: String?,
    val bytes: Long?,
    val width: Int?,
    val height: Int?,
    val altText: String?,
    /** False for a link to somewhere we do not control; deleting one removes only the row. */
    val stored: Boolean,
)

data class ImageUsageDTO(
    val image: ImageDTO,
    val usedBy: List<UsedByDTO>,
    val deletable: Boolean,
)

data class UsedByDTO(val productId: Long, val spuCode: String, val name: String)

/**
 * One screenful of the library.
 *
 * Shaped like the portal's other paged responses, plus [unusedCount] — how many images
 * nothing shows, across the whole library rather than this page. It is the number the
 * screen is really about: it says how much can be cleared out.
 */
data class ImageLibraryDTO(
    val content: List<ImageUsageDTO>,
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int,
    val unusedCount: Long,
)
