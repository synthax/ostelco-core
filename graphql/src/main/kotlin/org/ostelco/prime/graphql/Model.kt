package org.ostelco.prime.graphql

import org.ostelco.prime.model.Bundle
import org.ostelco.prime.model.Product
import org.ostelco.prime.model.PurchaseRecord
import org.ostelco.prime.model.Subscription
import org.ostelco.prime.model.Customer as Profile

data class GraphQLRequest(
        val query: String,
        val operationName: String? = null,
        val variables: Map<String, Any> = emptyMap())

data class Customer(
        val profile: Profile? = null,
        val bundles: Collection<Bundle>? = null,
        val subscriptions: Collection<Subscription>? = null,
        val products: Collection<Product>? = null,
        val purchases: Collection<PurchaseRecord>? = null)

data class Data(var customer: Customer? = null)

data class GraphQlResponse(var data: Data? = null, var errors: List<String>? = null)