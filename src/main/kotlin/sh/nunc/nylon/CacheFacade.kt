package sh.nunc.nylon

import mu.KotlinLogging
import net.jodah.failsafe.Failsafe
import net.jodah.failsafe.Fallback
import net.jodah.failsafe.Timeout
import org.aspectj.lang.ProceedingJoinPoint
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
            val cache = cacheManager.getCache(cacheName)
            cache?.let { valueCache ->
                valueCache[key]?.get()
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


    private fun insert(joinPoint: ProceedingJoinPoint, cacheName: String, cacheKey: String): Any? {

        val result = joinPoint.proceed()
        val time = clock.millis()

        val cache = cacheManager.getCache(cacheName)
        val iCache = cacheManager.getCache(getInsertionTimeCacheName(cacheName))
        cache?.let { realCache ->
            realCache.put(cacheKey, result)
            iCache?.put(cacheKey, time)
            logger.debug { "saved value $result at key $cacheKey" }
        }
        return result
    }

    fun insertNow(
        joinPoint: ProceedingJoinPoint,
        cacheName: String,
        cacheKey: String,
        timeoutMillis: Long
    ) : Any? {
        val timeout: Timeout<Any?> =
            Timeout.of(
                Duration.ofMillis(timeoutMillis)
            )
        val fallback: Fallback<Any?> =
            Fallback.of { null }
        return Failsafe.with(fallback, timeout)
            .get { _ -> insert(joinPoint, cacheName, cacheKey)}
    }

    fun updateInBackground(joinPoint: ProceedingJoinPoint, cacheName: String, cacheKey: String, oldValue: Any) {
        val fallback: Fallback<Any> =
            Fallback.of(oldValue)
        Failsafe.with(fallback)
            .getAsync { _ -> insert(joinPoint, cacheName, cacheKey)}
    }
}