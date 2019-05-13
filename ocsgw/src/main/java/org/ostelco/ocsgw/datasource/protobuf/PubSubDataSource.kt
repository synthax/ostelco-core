package org.ostelco.ocsgw.datasource.protobuf

import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.api.gax.batching.BatchingSettings
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.FixedTransportChannelProvider
import com.google.api.gax.rpc.TransportChannelProvider
import com.google.cloud.pubsub.v1.AckReplyConsumer
import com.google.cloud.pubsub.v1.MessageReceiver
import com.google.cloud.pubsub.v1.Publisher
import com.google.cloud.pubsub.v1.Subscriber
import com.google.protobuf.ByteString
import com.google.pubsub.v1.ProjectSubscriptionName
import com.google.pubsub.v1.ProjectTopicName
import com.google.pubsub.v1.PubsubMessage
import io.grpc.ManagedChannelBuilder
import org.ostelco.diameter.CreditControlContext
import org.ostelco.diameter.getLogger
import org.ostelco.ocs.api.*
import org.ostelco.ocsgw.datasource.DataSource
import org.threeten.bp.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


class PubSubDataSource(
        private val protobufDataSource: ProtobufDataSource,
        projectId: String,
        ccrTopicId: String,
        private val ccaTopicId: String,
        ccaSubscriptionId: String,
        activateSubscriptionId: String) : DataSource {

    private val logger by getLogger()

    private var singleThreadScheduledExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private var pubSubChannelProvider: TransportChannelProvider? = null
    private var publisher: Publisher

    init {

        val strSocketAddress = System.getenv("PUBSUB_EMULATOR_HOST")
        if (!strSocketAddress.isNullOrEmpty()) {
            val channel = ManagedChannelBuilder.forTarget(strSocketAddress).usePlaintext().build()
            // Create a publisher instance with default settings bound to the topic
            pubSubChannelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
        }

        // init publisher
        logger.info("Setting up Publisher for topic: {}", ccrTopicId)
        publisher = setupPublisherToTopic(projectId, ccrTopicId)

        // Instantiate an asynchronous message receiver
        setupPubSubSubscriber(projectId, ccaSubscriptionId) { message, consumer ->
            // handle incoming message, then ack/nack the received message
            val ccaInfo = CreditControlAnswerInfo.parseFrom(message)
            if (ccaInfo.resultCode != ResultCode.UNKNOWN) {
                logger.info("[<<] CreditControlAnswer for msisdn {} sessionId [{}]", ccaInfo.msisdn, ccaInfo.requestId)
                protobufDataSource.handleCcrAnswer(ccaInfo)
            }
            consumer.ack()
        }

        setupPubSubSubscriber(projectId, activateSubscriptionId) { message, consumer ->
            // handle incoming message, then ack/nack the received message
            protobufDataSource.handleActivateResponse(
                    ActivateResponse.parseFrom(message))
            consumer.ack()
        }

        initKeepAlive()
    }

    override fun init() {

    }

    override fun handleRequest(context: CreditControlContext) {

        logger.info("[>>] creditControlRequest for msisdn {} session id [{}] request number [{}]", context.creditControlRequest.msisdn, context.sessionId, context.creditControlRequest.ccRequestNumber?.integer32)

        val creditControlRequestInfo = protobufDataSource.handleRequest(context, ccaTopicId)

        if (creditControlRequestInfo != null) {
            sendRequest(creditControlRequestInfo)
        }
    }

    override fun isBlocked(msisdn: String): Boolean = protobufDataSource.isBlocked(msisdn)

    private fun sendRequest(creditControlRequestInfo : CreditControlRequestInfo) {
        val base64String = Base64.getEncoder().encodeToString(
                creditControlRequestInfo.toByteArray())
        val byteString = ByteString.copyFromUtf8(base64String)

        if (!byteString.isValidUtf8) {
            logger.warn("Could not convert creditControlRequestInfo to UTF-8")
            return
        }
        val pubsubMessage = PubsubMessage.newBuilder()
                .setMessageId(creditControlRequestInfo.requestId)
                .setData(byteString)
                .build()

        //schedule a message to be published, messages are automatically batched
        val future = publisher.publish(pubsubMessage)

        // add an asynchronous callback to handle success / failure
        ApiFutures.addCallback(future, object : ApiFutureCallback<String> {

            override fun onFailure(throwable: Throwable) {
                if (throwable is ApiException) {
                    // details on the API exception
                    logger.warn("Status code: {}", throwable.statusCode.code)
                    logger.warn("Retrying: {}", throwable.isRetryable)
                }
                logger.warn("Error sending CCR Request to PubSub")
            }

            override fun onSuccess(messageId: String) {
                // Once published, returns server-assigned message ids (unique within the topic)
                // logger.debug("Submitted message with request-id: {} successfully", messageId)
            }
        }, singleThreadScheduledExecutor)
    }

    private fun setupPublisherToTopic(projectId: String, topicId: String): Publisher {

        val batchingSettings = BatchingSettings.newBuilder().setIsEnabled(false).build()

        logger.info("Setting up Publisher for topic: {}", topicId)
        val topicName = ProjectTopicName.of(projectId, topicId)
        return pubSubChannelProvider
                ?.let { channelProvider ->
                    Publisher.newBuilder(topicName)
                            .setChannelProvider(channelProvider)
                            .setCredentialsProvider(NoCredentialsProvider())
                            .setBatchingSettings(batchingSettings)
                            .build()
                }
                ?: Publisher.newBuilder(topicName).setBatchingSettings(batchingSettings).build()
    }

    private fun setupPubSubSubscriber(projectId: String, subscriptionId: String, handler: (ByteString, AckReplyConsumer) -> Unit) {

        // init subscriber
        logger.info("Setting up Subscriber for subscription: {}", subscriptionId)
        val subscriptionName = ProjectSubscriptionName.of(projectId, subscriptionId)

        val receiver = MessageReceiver { message, consumer ->
            val base64String = message.data.toStringUtf8()
            handler(ByteString.copyFrom(Base64.getDecoder().decode(base64String)), consumer)
        }

        val subscriber: Subscriber?
        try {
            // Create a subscriber for "my-subscription-id" bound to the message receiver
            subscriber = pubSubChannelProvider
                    ?.let {channelProvider ->
                        Subscriber.newBuilder(subscriptionName, receiver)
                                .setChannelProvider(channelProvider)
                                .setCredentialsProvider(NoCredentialsProvider())
                                .build()
                    }
                    ?: Subscriber.newBuilder(subscriptionName, receiver)
                            .build()
            subscriber?.startAsync()?.awaitRunning()
        } finally {
            // stop receiving messages
            // subscriber?.stopAsync()
        }
    }

    /**
     * The keep alive messages are sent so the stream is always active-
     * This to keep latency low.
     */
    private fun initKeepAlive() {
        // this is used to keep low latency on the connection
        singleThreadScheduledExecutor.scheduleWithFixedDelay({
            val ccrInfo = CreditControlRequestInfo.newBuilder()
                    .setType(CreditControlRequestType.NONE)
                    .setRequestId(UUID.randomUUID().toString())
                    .setTopicId(ccaTopicId)
                    .build()
            sendRequest(ccrInfo)
        },
                5,
                2,
                TimeUnit.SECONDS)
    }
}