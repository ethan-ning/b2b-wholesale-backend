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
