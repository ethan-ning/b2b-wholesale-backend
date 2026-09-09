package com.acme.b2b.domain.catalog

/**
 * Whether dealers can see a product. Two states, not three: products arrive from the ERP
 * already real so nothing is ever draft, and "archived" would be a second name for
 * inactive — which invites a second behaviour.
 *
 * Deactivating is the portal's only way to take a product out of the catalog, and it
 * keeps the pricing and history a delete would take with it.
 */
enum class ProductStatus { ACTIVE, INACTIVE }
