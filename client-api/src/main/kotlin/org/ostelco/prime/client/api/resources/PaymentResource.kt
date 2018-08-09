package org.ostelco.prime.client.api.resources

import io.dropwizard.auth.Auth
import org.ostelco.prime.client.api.auth.AccessTokenPrincipal
import org.ostelco.prime.client.api.store.SubscriberDAO
import org.ostelco.prime.core.ApiError
import org.ostelco.prime.module.getResource
import org.ostelco.prime.paymentprocessor.PaymentProcessor
import org.ostelco.prime.paymentprocessor.core.SourceInfo
import javax.validation.constraints.NotNull
import javax.ws.rs.Consumes
import javax.ws.rs.Produces
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.QueryParam
import javax.ws.rs.core.Response

/**
 * Payment API.
 *
 */
@Path("/paymentSources")
class PaymentResource(private val dao: SubscriberDAO) {

    private val paymentProcessor by lazy { getResource<PaymentProcessor>() }

    @POST
    @Produces("application/json")
    fun createSource(@Auth token: AccessTokenPrincipal?,
                     @NotNull
                     @QueryParam("sourceId")
                     sourceId: String): Response {
        if (token == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .build()
        }

        val paymentProfile = dao.getPaymentProfile(token.name)

        if (paymentProfile.isLeft) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(asJson(paymentProfile.left().get()))
                    .build()
        }

        val result = paymentProcessor.addSource(paymentProfile.right().get().id, sourceId)

        return if (result.isRight) {
            Response.status(Response.Status.CREATED)
                    .entity(asJson(result.right().get()))
                    .build()
        } else {
            Response.status(Response.Status.BAD_GATEWAY)
                    .entity(asJson(result.left().get()))
                    .build()
        }
    }

    @GET
    @Produces("application/json")
    fun listSources(@Auth token: AccessTokenPrincipal?): Response {
        if (token == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .build()
        }

        val paymentProfile = dao.getPaymentProfile(token.name)

        if (paymentProfile.isLeft) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(asJson(paymentProfile.left().get()))
                    .build()
        }

        val result = paymentProcessor.getSavedSources(paymentProfile.right().get().id)

        return if (result.isRight) {
            Response.status(Response.Status.OK)
                    .entity(asJson(result))
                    .build()
        } else {
            Response.status(Response.Status.BAD_GATEWAY)
                    .entity(asJson(result.left().get()))
                    .build()
        }
    }

    @PUT
    fun setDefaultSource(@Auth token: AccessTokenPrincipal?,
                         @NotNull
                         @QueryParam("sourceId")
                         sourceId: String): Response {
        if (token == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .build()
        }

        val paymentProfile = dao.getPaymentProfile(token.name)

        if (paymentProfile.isLeft) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(asJson(paymentProfile.left().get()))
                    .build()
        }

        val result = paymentProcessor.setDefaultSource(paymentProfile.right().get().id, sourceId)

        return if (result.isRight) {
            Response.status(Response.Status.OK)
                    .entity(asJson(result.right().get()))
                    .build()
        } else {
            Response.status(Response.Status.BAD_GATEWAY)
                    .entity(asJson(result.left().get()))
                    .build()
        }
    }
}
