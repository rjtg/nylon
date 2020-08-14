package sh.nunc.nylon

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component


@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Nylon(val key: String, val softTtlMillis: Long, val timeoutMillis: Long, val cacheName: String, val jitter: Long = 0)

sealed class NylonState {
    data class Good(val value: Any) : NylonState()
    data class RefreshInBackground(val value: Any) : NylonState()
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

    private fun getChecked(nylon: Nylon, cacheKey: String) = cacheFacade.getFromCache(nylon.cacheName, cacheKey).let { nylonCacheChecker.check(nylon, it) }

    @Around("nylonPointcut()")
    @Throws(Throwable::class)
    fun nylonCache(joinPoint: ProceedingJoinPoint): Any? =
        joinPointUtil.extract(joinPoint).let { (nylon, cacheKey) -> 
            when (val cacheValue = getChecked(nylon, cacheKey)) {
                NylonState.FetchNow -> {
                    cacheFacade.insertNow(joinPoint, nylon, cacheKey)
                }
                is NylonState.RefreshInBackground -> {
                    cacheValue.value.also {
                        cacheFacade.updateInBackground(joinPoint, nylon, cacheKey, it)
                    }
                }
                is NylonState.Good -> {
                    cacheValue.value
                }
            }
        }
}

