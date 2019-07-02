package org.ostelco.prime.dsl

import org.ostelco.prime.model.Bundle
import org.ostelco.prime.model.Customer
import org.ostelco.prime.model.HasId
import org.ostelco.prime.model.Plan
import org.ostelco.prime.model.Product
import org.ostelco.prime.model.PurchaseRecord
import org.ostelco.prime.model.Region
import org.ostelco.prime.model.ScanInformation
import org.ostelco.prime.model.Subscription
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.customerToBundleRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.customerToSimProfileRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.identifiesRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.purchaseRecordRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.referredRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.scanInformationRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.simProfileRegionRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.subscribesToPlanRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.subscriptionRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.subscriptionSimProfileRelation
import org.ostelco.prime.storage.graph.Neo4jStoreSingleton.subscriptionToBundleRelation
import org.ostelco.prime.storage.graph.RelationType
import org.ostelco.prime.storage.graph.model.Identity
import org.ostelco.prime.storage.graph.model.SimProfile
import kotlin.reflect.KClass

data class RelatedFromClause<FROM : HasId, TO : HasId>(
        val relationType: RelationType<FROM, *, TO>,
        val fromId: String)

data class RelatedToClause<FROM : HasId, TO : HasId>(
        val relationType: RelationType<FROM, *, TO>,
        val toId: String)

data class RelationFromClause<FROM : HasId, RELATION, TO : HasId>(
        val relationType: RelationType<FROM, RELATION, TO>,
        val fromId: String)

data class RelationExpression<FROM : HasId, RELATION, TO : HasId>(
        val relationType: RelationType<FROM, RELATION, TO>,
        val fromId: String,
        val toId: String,
        val relation: RELATION? = null)

data class PartialRelationExpression<FROM : HasId, RELATION, TO : HasId>(
        val relationType: RelationType<FROM, RELATION, TO>,
        val fromId: String,
        val toId: String,
        val relation: RELATION? = null) {

    infix fun using(relation: RELATION) = RelationExpression(
            relationType = relationType,
            fromId = fromId,
            toId = toId,
            relation = relation)
}


//data class RelationToClause<FROM : HasId, TO : HasId>(
//        val relationType: RelationType<FROM, *, TO>,
//        val toId: String)

// (Identity) -[IDENTIFIES]-> (Customer)
// (Customer) -[HAS_SUBSCRIPTION]-> (Subscription)
// (Customer) -[HAS_BUNDLE]-> (Bundle)
// (Customer) -[HAS_SIM_PROFILE]-> (SimProfile)
// (Customer) -[SUBSCRIBES_TO_PLAN]-> (Plan)
// (Subscription) -[LINKED_TO_BUNDLE]-> (Bundle)
// (Customer) -[PURCHASED]-> (Product)
// (Customer) -[REFERRED]-> (Customer)
// (Offer) -[OFFERED_TO_SEGMENT]-> (Segment)
// (Offer) -[OFFER_HAS_PRODUCT]-> (Product)
// (Customer) -[BELONG_TO_SEGMENT]-> (Segment)
// (Customer) -[EKYC_SCAN]-> (ScanInformation)
// (Customer) -[BELONG_TO_REGION]-> (Region)
// (SimProfile) -[SIM_PROFILE_FOR_REGION]-> (Region)
// (Subscription) -[SUBSCRIPTION_UNDER_SIM_PROFILE]-> (SimProfile)

open class EntityContext<E : HasId>(val entityClass: KClass<E>, open val id: String)

data class IdentityContext(override val id: String) : EntityContext<Identity>(Identity::class, id) {
    infix fun identifies(customer: CustomerContext) = PartialRelationExpression(
            relationType = identifiesRelation,
            fromId = id,
            toId = customer.id)
}

data class CustomerContext(override val id: String) : EntityContext<Customer>(Customer::class, id) {

    infix fun referred(customer: CustomerContext) = RelationExpression(
            relationType = referredRelation,
            fromId = id,
            toId = customer.id)

    infix fun hasBundle(bundle: BundleContext) = RelationExpression(
            relationType = customerToBundleRelation,
            fromId = id,
            toId = bundle.id)

    infix fun has(simProfile: SimProfileContext) = RelationExpression(
            relationType = customerToSimProfileRelation,
            fromId = id,
            toId = simProfile.id)

    infix fun subscribesTo(subscription: SubscriptionContext) = RelationExpression(
            relationType = subscriptionRelation,
            fromId = id,
            toId = subscription.id)

    infix fun subscribesTo(plan: PlanContext) = PartialRelationExpression(
            relationType = subscribesToPlanRelation,
            fromId = id,
            toId = plan.id)

    infix fun purchased(product: ProductContext) = PartialRelationExpression(
            relationType = purchaseRecordRelation,
            fromId = id,
            toId = product.id)
}

data class BundleContext(override val id: String) : EntityContext<Bundle>(Bundle::class, id)
data class RegionContext(override val id: String) : EntityContext<Region>(Region::class, id)

data class SimProfileContext(override val id: String) : EntityContext<SimProfile>(SimProfile::class, id) {

    infix fun isFor(region: RegionContext) = RelationExpression(
            relationType = simProfileRegionRelation,
            fromId = id,
            toId = region.id)
}

data class SubscriptionContext(override val id: String) : EntityContext<Subscription>(Subscription::class, id) {

    infix fun consumesFrom(bundle: BundleContext) = PartialRelationExpression(
            relationType = subscriptionToBundleRelation,
            fromId = id,
            toId = bundle.id)

    infix fun isUnder(simProfile: SimProfileContext) = RelationExpression(
            relationType = subscriptionSimProfileRelation,
            fromId = id,
            toId = simProfile.id)
}

data class ScanInfoContext(override val id: String) : EntityContext<ScanInformation>(ScanInformation::class, id)
data class PlanContext(override val id: String) : EntityContext<Plan>(Plan::class, id)
data class ProductContext(override val id: String) : EntityContext<Product>(Product::class, id)

//
// Identity
//

infix fun Identity.Companion.withId(id: String) = IdentityContext(id)

//
// Customer
//

infix fun Customer.Companion.withId(id: String): CustomerContext = CustomerContext(id)

infix fun Customer.Companion.identifiedBy(identity: IdentityContext) =
        RelatedFromClause(
                relationType = identifiesRelation,
                fromId = identity.id
        )

infix fun Customer.Companion.withKyc(scanInfo: ScanInfoContext) =
        RelatedToClause(
                relationType = scanInformationRelation,
                toId = scanInfo.id
        )

infix fun Customer.Companion.referred(customer: CustomerContext) =
        RelatedToClause(
                relationType = referredRelation,
                toId = customer.id
        )

infix fun Customer.Companion.referredBy(customer: CustomerContext) =
        RelatedFromClause(
                relationType = referredRelation,
                fromId = customer.id
        )

//
// Bundle
//

infix fun Bundle.Companion.withId(id: String): BundleContext = BundleContext(id)

infix fun Bundle.Companion.forCustomer(customer: CustomerContext) =
        RelatedFromClause(
                relationType = customerToBundleRelation,
                fromId = customer.id
        )

//
// Region
//

infix fun Region.Companion.withCode(id: String): RegionContext = RegionContext(id)

infix fun Region.Companion.linkedToSimProfile(simProfile: SimProfileContext) =
        RelatedFromClause(
                relationType = simProfileRegionRelation,
                fromId = simProfile.id
        )

//
// SimProfiles
//

infix fun SimProfile.Companion.withId(id: String): SimProfileContext = SimProfileContext(id)

infix fun SimProfile.Companion.linkedToRegion(region: RegionContext) =
        RelatedToClause(
                relationType = simProfileRegionRelation,
                toId = region.id
        )

infix fun SimProfile.Companion.forCustomer(customer: CustomerContext) =
        RelatedFromClause(
                relationType = customerToSimProfileRelation,
                fromId = customer.id
        )

//
// Subscription
//

infix fun Subscription.Companion.withMsisdn(id: String): SubscriptionContext = SubscriptionContext(id)

infix fun Subscription.Companion.under(simProfile: SimProfileContext) =
        RelatedToClause(
                relationType = subscriptionSimProfileRelation,
                toId = simProfile.id
        )

infix fun Subscription.Companion.subscribedBy(customer: CustomerContext) =
        RelatedFromClause(
                relationType = subscriptionRelation,
                fromId = customer.id
        )

//
// ScanInfo
//

infix fun ScanInformation.Companion.withId(id: String): ScanInfoContext = ScanInfoContext(id)

infix fun ScanInformation.Companion.forCustomer(customer: CustomerContext) =
        RelatedFromClause(
                relationType = scanInformationRelation,
                fromId = customer.id
        )

//
// Plan
//

infix fun Plan.Companion.withId(id: String): PlanContext = PlanContext(id)

infix fun Plan.Companion.forCustomer(customer: CustomerContext) =
        RelatedFromClause(
                relationType = subscribesToPlanRelation,
                fromId = customer.id
        )

//
// Product
//

infix fun Product.Companion.withSku(id: String): ProductContext = ProductContext(id)

infix fun Product.Companion.purchasedBy(customer: CustomerContext) =
        RelatedFromClause(
                relationType = purchaseRecordRelation,
                fromId = customer.id
        )

//
// Purchase Record
//
infix fun PurchaseRecord.Companion.forPurchasesBy(customer: CustomerContext) =
        RelationFromClause(
                relationType = purchaseRecordRelation,
                fromId = customer.id
        )