package com.acme.b2b.domain.catalog

/**
 * Whether dealers can see a product.
 *
 * There is no draft state: products originate in the ERP and arrive already real, so
 * nothing here is ever half-written. And there is no archived state distinct from
 * inactive — both mean "do not show this to dealers", and two names for one rule
 * invites two behaviours.
 *
 * Deactivating is the portal's only way to remove a product from the catalog. It is
 * not a delete: pricing, MAP and history survive, and the next ERP sync will not
 * resurrect it as though nothing happened.
 */
enum class ProductStatus { ACTIVE, INACTIVE }
