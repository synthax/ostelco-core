package org.ostelco.prime.apierror

import org.ostelco.prime.getLogger
import org.ostelco.prime.jsonmapper.asJson
import org.ostelco.prime.paymentprocessor.core.PaymentError
import org.ostelco.prime.simmanager.SimManagerError
import org.ostelco.prime.storage.StoreError
import javax.ws.rs.core.Response

sealed class ApiError(val message: String, val errorCode: ApiErrorCode, val error: InternalError?) {
    open var status : Int = 0
}

class InternalServerError(description: String, errorCode: ApiErrorCode, error: InternalError? = null) : ApiError(description, errorCode, error) {
    override var status : Int = Response.Status.INTERNAL_SERVER_ERROR.statusCode
}

class BadRequestError(description: String, errorCode: ApiErrorCode, error: InternalError? = null) : ApiError(description, errorCode, error) {
    override var status : Int = Response.Status.BAD_REQUEST.statusCode
}

class ForbiddenError(description: String, errorCode: ApiErrorCode, error: InternalError? = null) : ApiError(description, errorCode, error) {
    override var status : Int = Response.Status.FORBIDDEN.statusCode
}

class NotFoundError(description: String, errorCode: ApiErrorCode, error: InternalError? = null) : ApiError(description, errorCode, error) {
    override var status : Int = Response.Status.NOT_FOUND.statusCode
}

object ApiErrorMapper {

    val logger by getLogger()

    fun mapPaymentErrorToApiError(description: String, errorCode: ApiErrorCode, paymentError: PaymentError) : ApiError {
        logger.error("description: $description, errorCode: $errorCode, paymentError: ${asJson(paymentError)}")
        return when(paymentError) {
            is org.ostelco.prime.paymentprocessor.core.PlanAlredyPurchasedError -> org.ostelco.prime.apierror.ForbiddenError(description, errorCode, paymentError)
            is org.ostelco.prime.paymentprocessor.core.ForbiddenError  ->  org.ostelco.prime.apierror.ForbiddenError(description, errorCode, paymentError)
            // FIXME vihang: remove PaymentError from BadGatewayError
            is org.ostelco.prime.paymentprocessor.core.BadGatewayError -> org.ostelco.prime.apierror.InternalServerError(description, errorCode, paymentError)
            is org.ostelco.prime.paymentprocessor.core.NotFoundError -> org.ostelco.prime.apierror.NotFoundError(description, errorCode, paymentError)
        }
    }

    fun mapStorageErrorToApiError(description: String, errorCode: ApiErrorCode, storeError: StoreError) : ApiError {
        logger.error("description: $description, errorCode: $errorCode, storeError: ${asJson(storeError)}")
        return when(storeError) {
            is org.ostelco.prime.storage.NotFoundError  ->  org.ostelco.prime.apierror.NotFoundError(description, errorCode, storeError)
            is org.ostelco.prime.storage.AlreadyExistsError  ->  org.ostelco.prime.apierror.ForbiddenError(description, errorCode, storeError)
            // FIXME vihang: remove StoreError from BadGatewayError
            is org.ostelco.prime.storage.NotCreatedError  ->  org.ostelco.prime.apierror.InternalServerError(description, errorCode)
            is org.ostelco.prime.storage.NotUpdatedError  ->  org.ostelco.prime.apierror.InternalServerError(description, errorCode)
            is org.ostelco.prime.storage.NotDeletedError  ->  org.ostelco.prime.apierror.InternalServerError(description, errorCode)
            is org.ostelco.prime.storage.ValidationError  ->  org.ostelco.prime.apierror.ForbiddenError(description, errorCode, storeError)
            is org.ostelco.prime.storage.FileDownloadError  ->  org.ostelco.prime.apierror.InternalServerError(description, errorCode)
            is org.ostelco.prime.storage.FileDeleteError  ->  org.ostelco.prime.apierror.InternalServerError(description, errorCode)
            is org.ostelco.prime.storage.SystemError  ->  org.ostelco.prime.apierror.InternalServerError(description, errorCode)
            is org.ostelco.prime.storage.DatabaseError  ->  org.ostelco.prime.apierror.InternalServerError(description, errorCode)
        }
    }

    fun mapSimManagerErrorToApiError(description: String, errorCode: ApiErrorCode, simManagerError: SimManagerError) : ApiError {
        logger.error("description: $description, errorCode: $errorCode, simManagerError: ${asJson(simManagerError)}")
        return when (simManagerError) {
            is org.ostelco.prime.simmanager.NotFoundError -> org.ostelco.prime.apierror.NotFoundError(description, errorCode, simManagerError)
            is org.ostelco.prime.simmanager.NotUpdatedError -> org.ostelco.prime.apierror.BadRequestError(description, errorCode, simManagerError)
            is org.ostelco.prime.simmanager.ForbiddenError -> org.ostelco.prime.apierror.ForbiddenError(description, errorCode, simManagerError)
            is org.ostelco.prime.simmanager.AdapterError -> org.ostelco.prime.apierror.InternalServerError(description, errorCode, simManagerError)
            is org.ostelco.prime.simmanager.DatabaseError -> org.ostelco.prime.apierror.InternalServerError(description, errorCode, simManagerError)
            is org.ostelco.prime.simmanager.SystemError -> org.ostelco.prime.apierror.InternalServerError(description, errorCode, simManagerError)
        }
    }
}
