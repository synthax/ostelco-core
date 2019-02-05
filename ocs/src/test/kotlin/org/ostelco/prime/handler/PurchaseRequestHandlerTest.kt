package org.ostelco.prime.handler

import arrow.core.Either
import arrow.core.right
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.ostelco.prime.disruptor.EventProducer
import org.ostelco.prime.model.Bundle
import org.ostelco.prime.model.Identity
import org.ostelco.prime.model.Product
import org.ostelco.prime.model.PurchaseRecord
import org.ostelco.prime.storage.ClientDataSource
import org.ostelco.prime.storage.StoreError
import org.ostelco.prime.storage.legacy.Products.DATA_TOPUP_3GB

class PurchaseRequestHandlerTest {

    @Rule
    @JvmField
    var mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    lateinit var storage: ClientDataSource

    @Mock
    lateinit var producer: EventProducer

    private lateinit var purchaseRequestHandler: PurchaseRequestHandler

    @Before
    fun setUp() {

        `when`< Either<StoreError, Product>>(storage.getProduct(IDENTITY, DATA_TOPUP_3GB.sku))
                .thenReturn(DATA_TOPUP_3GB.right())

        `when`< Either<StoreError, Collection<Bundle>>>(storage.getBundles(IDENTITY))
                .thenReturn(listOf(Bundle(id = BUNDLE_ID, balance = 0)).right())

        this.purchaseRequestHandler = PurchaseRequestHandler(producer, storage)
    }

    // FIXME vihang: handlePurchaseRequestTest is marked to be ignored, until it is fixed
    @Ignore
    @Test
    fun handlePurchaseRequestTest() {

        val sku = DATA_TOPUP_3GB.sku

        // Process a little
        purchaseRequestHandler.handlePurchaseRequest(IDENTITY, sku)

        // Then verify that the appropriate actions has been performed.
        val topupBytes = DATA_TOPUP_3GB.properties["noOfBytes"]?.toLong()
                ?: throw Exception("Missing property 'noOfBytes' in product sku: $sku")

        val capturedPurchaseRecord = ArgumentCaptor.forClass(PurchaseRecord::class.java)

        assertEquals(DATA_TOPUP_3GB, capturedPurchaseRecord.value.product)

        verify<EventProducer>(producer).topupDataBundleBalanceEvent(
                requestId = TOPUP_REQUEST_ID,
                bundleId = BUNDLE_ID,
                bytes = topupBytes)
    }

    companion object {

        private const val BUNDLE_ID = "foo@bar.com"
        private const val TOPUP_REQUEST_ID = "req-id"
        private val IDENTITY = Identity(id = "foo@bar.com", type = "EMAIL", provider = "email")
    }

    // https://github.com/mockito/mockito/issues/1255
    fun <T : Any> safeEq(value: T): T = ArgumentMatchers.eq(value) ?: value
}
