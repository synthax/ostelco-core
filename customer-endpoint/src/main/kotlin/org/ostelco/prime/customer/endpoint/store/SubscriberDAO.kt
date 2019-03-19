package org.ostelco.prime.customer.endpoint.store

import arrow.core.Either
import org.ostelco.prime.apierror.ApiError
import org.ostelco.prime.customer.endpoint.model.Person
import org.ostelco.prime.model.ApplicationToken
import org.ostelco.prime.model.Bundle
import org.ostelco.prime.model.Customer
import org.ostelco.prime.model.Identity
import org.ostelco.prime.model.Product
import org.ostelco.prime.model.PurchaseRecord
import org.ostelco.prime.model.RegionDetails
import org.ostelco.prime.model.ScanInformation
import org.ostelco.prime.model.Subscription
import org.ostelco.prime.paymentprocessor.core.ProductInfo
import org.ostelco.prime.paymentprocessor.core.SourceDetailsInfo
import org.ostelco.prime.paymentprocessor.core.SourceInfo


/**
 *
 */
interface SubscriberDAO {

    //
    // Customer
    //

    fun getCustomer(identity: Identity): Either<ApiError, Customer>

    fun createCustomer(identity: Identity, profile: Customer, referredBy: String?): Either<ApiError, Customer>

    fun updateCustomer(identity: Identity, profile: Customer): Either<ApiError, Customer>

    //
    // Regions
    //
    fun getRegions(identity: Identity): Either<ApiError, Collection<RegionDetails>>

    //
    // Subscriptions
    //

    fun getSubscriptions(identity: Identity): Either<ApiError, Collection<Subscription>>

    fun createSubscription(identity: Identity): Either<ApiError, Subscription>

    //
    // Bundle
    //
    fun getBundles(identity: Identity): Either<ApiError, Collection<Bundle>>

    //
    // Products
    //

    fun getPurchaseHistory(identity: Identity): Either<ApiError, Collection<PurchaseRecord>>

    fun getProduct(identity: Identity, sku: String): Either<ApiError, Product>

    fun getProducts(identity: Identity): Either<ApiError, Collection<Product>>

    fun purchaseProduct(identity: Identity, sku: String, sourceId: String?, saveCard: Boolean): Either<ApiError, ProductInfo>

    //
    // Payment
    //

    fun createSource(identity: Identity, sourceId: String): Either<ApiError, SourceInfo>

    fun setDefaultSource(identity: Identity, sourceId: String): Either<ApiError, SourceInfo>

    fun listSources(identity: Identity): Either<ApiError, List<SourceDetailsInfo>>

    fun removeSource(identity: Identity, sourceId: String): Either<ApiError, SourceInfo>

    fun getStripeEphemeralKey(identity: Identity, apiVersion: String): Either<ApiError, String>

    //
    // Referrals
    //

    fun getReferrals(identity: Identity): Either<ApiError, Collection<Person>>

    fun getReferredBy(identity: Identity): Either<ApiError, Person>

    //
    // eKYC
    //

    fun createNewJumioScanId(identity: Identity, countryCode: String): Either<ApiError, ScanInformation>

    fun getCountryCodeForScan(scanId: String): Either<ApiError, String>

    fun getScanInformation(identity: Identity, scanId: String): Either<ApiError, ScanInformation>

    fun getCustomerMyInfoData(identity: Identity, authorisationCode: String): Either<ApiError, String>

    fun checkIdNumberUsingDave(identity: Identity): Either<ApiError, Unit>

    fun saveProfile(identity: Identity): Either<ApiError, Unit>

    //
    // Token
    //

    fun storeApplicationToken(customerId: String, applicationToken: ApplicationToken): Either<ApiError, ApplicationToken>

    companion object {

        /**
         * Profile is only valid when name and email set.
         */
        fun isValidProfile(profile: Customer?): Boolean {
            return (profile != null
                    && !profile.name.isEmpty()
                    && !profile.email.isEmpty())
        }

        /**
         * The application token is only valid if token,
         * applicationID and token type is set.
         */
        fun isValidApplicationToken(appToken: ApplicationToken?): Boolean {
            return (appToken != null
                    && !appToken.token.isEmpty()
                    && !appToken.applicationID.isEmpty()
                    && !appToken.tokenType.isEmpty())
        }
    }
}