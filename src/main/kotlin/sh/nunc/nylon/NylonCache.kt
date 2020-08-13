package sh.nunc.nylon

import mu.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Nylon(val key: String, val softTtlMillis: Long, val timeoutMillis: Long, val cacheName: String, val jitter: Long = 0)

sealed class NylonState {
    data class Good(val value: Any) : NylonState()
    data class RefreshInBackGround(val value: Any) : NylonState()
    object FetchNow : NylonState()
}

sealed class NylonCacheValue{
    data class Found(val value: Any, val insertionTime: Long):NylonCacheValue()
    object Missing:NylonCacheValue()
}


@Component
@Aspect
class NylonAspectRedis(@Autowired private val joinPointUtil: JoinPointUtil, @Autowired private val cacheFacade: CacheFacade, @Autowired private val nylonCacheChecker: NylonCacheChecker) {

    @Pointcut("@annotation(sh.nunc.nylon.Nylon)")
    fun nylonPointcut() {
        //just a pointcut
    }

    @Around("nylonPointcut()")
    @Throws(Throwable::class)
    fun nylonCache(joinPoint: ProceedingJoinPoint): Any? {
        val (nylon, cacheKey) = joinPointUtil.extract(joinPoint)
        return when(val cacheValue = cacheFacade.getFromCache(nylon.cacheName, cacheKey).let { nylonCacheChecker.check(nylon, it)}) {
            NylonState.FetchNow -> {
                logger.debug { "fetching value downstream. Timeout: ${nylon.timeoutMillis} ms." }
                cacheFacade.insertNow(joinPoint, nylon.cacheName, cacheKey, nylon.timeoutMillis)
            }
            is NylonState.RefreshInBackGround -> {
                logger.debug { "Using cached value. refreshing value in background." }
                cacheFacade.updateInBackground(joinPoint, nylon.cacheName, cacheKey, cacheValue.value)
                cacheValue.value
            }
            is NylonState.Good -> {
                logger.debug { "Using cached value." }
                cacheValue.value
            }
        }
    }

}

