package com.acme.b2b.domain.customer

import com.acme.b2b.types.TierId

data class CustomerTier(val id: TierId, val name: String, val sortOrder: Int)
