package sh.nunc.nylon

import mu.KotlinLogging
import net.jodah.failsafe.Failsafe
import net.jodah.failsafe.Fallback
import net.jodah.failsafe.Timeout
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Component
class CacheFacade(@Autowired private val cacheManager: CacheManager, @Autowired private val clock: Clock) {

    fun getFromCache(cacheName:String, key:String): NylonCacheValue {
        return getCacheValue(cacheName, key)?.let {v ->
            getInsertionTimeForCacheValue(cacheName, key)?.let {t->
                NylonCacheValue.Found(v, t)
            }
        } ?: NylonCacheValue.Missing
    }

    private fun getCacheValue(cacheName: String, key: String): Any? {
        return try {
            cacheManager.getCache(cacheName)?.let {
                it[key]?.get()
            }
        } catch (e: RuntimeException){
            logger.warn {"problem retrieving cached value: $e"}
            null
        }
    }

    private fun getInsertionTimeForCacheValue(cacheName: String, key: String) : Long? {
        return try {
            getCacheValue(getInsertionTimeCacheName(cacheName), key) as Long?
        } catch (e: RuntimeException) {
            logger.warn {"problem getting insertion time for cached value: $e"}
            null
        }
    }

    private fun getInsertionTimeCacheName(cacheName: String) = "${cacheName}__NYLON_T"


    private fun insert(cacheName: String, cacheKey: String, newValue: Any?) {

        val time = clock.millis()

        val valueCache = cacheManager.getCache(cacheName)
        val timeCache = cacheManager.getCache(getInsertionTimeCacheName(cacheName))
        if (valueCache != null && timeCache != null) {
            valueCache.put(cacheKey, newValue)
            timeCache.put(cacheKey, time)
            logger.debug { "saved value $newValue at key $cacheKey" }
        }
    }

    private fun  <T> fallbackValue(type: Class<T>):T?{
        return null
    }

    fun insertNow(
        joinPoint: ProceedingJoinPoint,
        nylon: Nylon,
        cacheKey: String
    ) : Any? {
        val timeout: Timeout<Any?> =
            Timeout.of(
                Duration.ofMillis(nylon.timeoutMillis)
            )
        val returnType = (joinPoint.signature as MethodSignature).returnType
        val fallback: Fallback<Any?> =
            Fallback.of(fallbackValue(returnType))
        logger.debug { "fetching value downstream. Timeout: ${nylon.timeoutMillis} ms." }
        return Failsafe.with(fallback, timeout)
            .onFailure { e -> logger.warn { "failed to fetch downstream element. ${e.failure}" } }
            .get { _ -> joinPoint.proceed()?.also { insert(nylon.cacheName, cacheKey, it)}}
    }

    fun insertInBackground(joinPoint: ProceedingJoinPoint, nylon: Nylon, cacheKey: String) {
        val timeout: Timeout<Any?> =
            Timeout.of(
                Duration.ofMillis(nylon.timeoutMillis)
            )
        logger.debug { "Using cached value. refreshing value in background." }
        Failsafe.with(timeout)
            .onFailure { e -> logger.warn { "failed to fetch downstream element. ${e.failure}" } }
            .getAsync { _ -> joinPoint.proceed()?.also {insert(nylon.cacheName, cacheKey, it)}}
    }
}