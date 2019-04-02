package org.ostelco.simcards.admin

import com.codahale.metrics.health.HealthCheck
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeName
import io.dropwizard.client.HttpClientBuilder
import io.dropwizard.jdbi3.JdbiFactory
import io.dropwizard.setup.Environment
import org.apache.http.impl.client.CloseableHttpClient
import org.ostelco.dropwizardutils.OpenapiResourceAdder
import org.ostelco.prime.module.PrimeModule
import org.ostelco.sim.es2plus.ES2PlusIncomingHeadersFilter
import org.ostelco.sim.es2plus.SmDpPlusCallbackResource
import org.ostelco.simcards.admin.ConfigRegistry.config
import org.ostelco.simcards.admin.ResourceRegistry.simInventoryResource
import org.ostelco.simcards.hss.*
import org.ostelco.simcards.inventory.*

/**
 * The SIM manager
 * is an component that inputs inhales SIM batches
 * from SIM profile factories (physical or esim). It then facilitates
 * activation of SIM profiles to MSISDNs.   A typical interaction is
 * "find me a sim profile for this MSISDN for this HLR" , and then
 * "activate that profile".   The activation will typically involve
 * at least talking to a HLR to permit user equipment to use the
 * SIM profile to authenticate, and possibly also an SM-DP+ to
 * activate a SIM profile (via its ICCID and possible an EID).
 * The inventory can then serve as an intermidiary between the
 * rest of the BSS and the OSS in the form of HSS and SM-DP+.
 */
@JsonTypeName("sim-manager")
class SimAdministrationModule : PrimeModule {

    private lateinit var DAO: SimInventoryDAO

    @JsonProperty("config")
    fun setConfig(config: SimAdministrationConfiguration) {
        ConfigRegistry.config = config
    }

    fun getDAO() = DAO

    override fun init(env: Environment) {
        val factory = JdbiFactory()
        val jdbi = factory.build(env,
                config.database, "postgresql")
                .installPlugins()
        DAO = SimInventoryDAO(SimInventoryDBWrapperImpl(jdbi.onDemand(SimInventoryDB::class.java)))

        val profileVendorCallbackHandler = SimInventoryCallbackService(DAO)

        val httpClient = HttpClientBuilder(env)
                .using(config.httpClient)
                .build("SIM inventory")
        val jerseyEnv = env.jersey()

        OpenapiResourceAdder.addOpenapiResourceToJerseyEnv(jerseyEnv, config.openApi)
        ES2PlusIncomingHeadersFilter.addEs2PlusDefaultFiltersAndInterceptors(jerseyEnv)

        simInventoryResource = SimInventoryResource(SimInventoryApi(httpClient, config, DAO))
        jerseyEnv.register(simInventoryResource)
        jerseyEnv.register(SmDpPlusCallbackResource(profileVendorCallbackHandler))


        val dispatcher = makeHssDispatcher(
                hssAdapterConfig = config.hssAdapter,
                hssVendorConfigs = config.hssVendors,
                httpClient = httpClient,
                healthCheckRegistrar = object : HealthCheckRegistrar {
                    override fun registerHealthCheck(name: String, healthCheck: HealthCheck) {
                        env.healthChecks().register(name, healthCheck)
                    }
                })

        var hssAdapters = SimManagerToHssDispatcherAdapter(
                dispatcher = dispatcher,
                simInventoryDAO = this.DAO
        )

        env.admin().addTask(PreallocateProfilesTask(
                simInventoryDAO = this.DAO,
                httpClient = httpClient,
                hssAdapterProxy = hssAdapters,
                profileVendors = config.profileVendors));
    }


    // XXX Implement a feature-flag so that when we want to switch from built in
    //     direct access to HSSes, to adapter-mediated access, we can do that easily
    //     via config.
    private fun makeHssDispatcher(
            hssAdapterConfig: HssAdapterConfig?,
            hssVendorConfigs: List<HssConfig>,
            httpClient: CloseableHttpClient,
            healthCheckRegistrar: HealthCheckRegistrar): HssDispatcher {

        if (hssAdapterConfig != null) {
            return HssGrpcAdapter(
                    host = hssAdapterConfig.hostname,
                    port = hssAdapterConfig.port)
        } else if (hssVendorConfigs != null) {

            val dispatchers = mutableSetOf<HssDispatcher>()

            for (config in config.hssVendors) {
                dispatchers.add(
                        SimpleHssDispatcher(
                                name = config.name,
                                httpClient = httpClient,
                                config = config))
            }

            return DirectHssDispatcher(
                    hssConfigs = config.hssVendors,
                    httpClient = httpClient,
                    healthCheckRegistrar = healthCheckRegistrar)
        } else {
            throw RuntimeException("Unable to find HSS adapter config, please check config")
        }
    }
}

object ConfigRegistry {
    lateinit var config: SimAdministrationConfiguration
}

object ResourceRegistry {
    lateinit var simInventoryResource: SimInventoryResource
}

object ApiRegistry {
    lateinit var simInventoryApi: SimInventoryApi
}