package com.acme.b2b.domain.catalog

/**
 * Whether dealers can see a product. The portal's only lever over a product.
 *
 * Named for what it does rather than "active", which the ERP already uses for something
 * else: Sellfox marks a commodity 在售 or not, and only 在售 ones are imported at all. A
 * product that stops being on sale leaves the catalog by not being imported, never by
 * this flag — so the two never had to mean the same thing, and sharing a word invited
 * them to.
 *
 * Hiding is also the portal's only way to withdraw a product: there is no delete, because
 * the pricing and history hang off the row and the next sync would bring it back anyway.
 */
enum class ProductVisibility { VISIBLE, HIDDEN }
