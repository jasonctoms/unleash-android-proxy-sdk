package io.getunleash.polling

import io.getunleash.UnleashConfig
import io.getunleash.UnleashContext
import io.getunleash.cache.ToggleCache
import io.getunleash.data.FetchResponse
import io.getunleash.data.Toggle
import org.slf4j.LoggerFactory
import java.util.Timer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.fixedRateTimer

class AutoPollingPolicy(
    override val unleashFetcher: UnleashFetcher,
    override val cache: ToggleCache,
    override val config: UnleashConfig,
    override var context: UnleashContext,
    private val autoPollingConfig: AutoPollingMode
) :
    RefreshPolicy(
        unleashFetcher = unleashFetcher,
        cache = cache,
        logger = LoggerFactory.getLogger("io.getunleash.polling.AutoPollingPolicy"),
        config = config,
        context = context
    ) {
    private val listeners: MutableList<ToggleUpdatedListener> = mutableListOf()
    private val initialized = AtomicBoolean(false)
    private val initFuture = CompletableFuture<FetchResponse>()
    private val timer: Timer

    init {
        listeners.addAll(autoPollingConfig.togglesUpdatedListeners)
        timer =
            fixedRateTimer("unleash_toggles_fetcher", initialDelay = 0L, daemon = true, period = autoPollingConfig.pollRateInSeconds) {
                updateToggles()
                if (!initialized.getAndSet(true)) {
                    initFuture.complete(null)
                }
            }
    }


    override fun getConfigurationAsync(): CompletableFuture<Map<String, Toggle>> {
        return if (this.initFuture.isDone) {
            CompletableFuture.completedFuture(super.readToggleCache())
        } else {
            this.initFuture.thenApplyAsync { super.readToggleCache() }
        }
    }

    private fun updateToggles() {
        try {
            val response = super.fetcher().getTogglesAsync(context).get()
            val cached = super.readToggleCache()
            if (response.isFetched() && cached != response.toggles) {
                super.writeToggleCache(response.toggles)
                this.broadcastTogglesUpdated()
            }
        } catch (e: Exception) {
            logger.warn("Exception in AutoPollingCachePolicy", e)
        }
    }

    fun broadcastTogglesUpdated() {
        synchronized(listeners) {
            listeners.forEach {
                it.onTogglesUpdated()
            }
        }
    }

    override fun close() {
        super.close()
        this.timer.cancel()
        this.listeners.clear()
    }
}