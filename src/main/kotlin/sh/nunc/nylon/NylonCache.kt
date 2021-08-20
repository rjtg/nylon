package sh.nunc.nylon

import mu.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

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

    @Pointcut("@annotation(sh.nunc.nylon.Nylon) || @within(sh.nunc.nylon.Nylon)")
    fun nylonPointcut() {
        //just a pointcut
    }

    private fun getChecked(nylon: Nylon, cacheKey: String) = cacheFacade.getFromCache(nylon.cacheName, cacheKey).let { nylonCacheChecker.check(nylon, it) }

    @Around("nylonPointcut()")
    @Throws(Throwable::class)
    fun nylonCache(joinPoint: ProceedingJoinPoint): Any? =
        when (val extract = joinPointUtil.extract(joinPoint)) {
            is NylonJoinPointExtract.ValidExtract -> {
                when (val cacheValue = getChecked(extract.nylon, extract.cacheKey)) {
                    NylonState.FetchNow -> {
                        cacheFacade.insertNow(joinPoint, extract.nylon, extract.cacheKey)
                    }
                    is NylonState.RefreshInBackground -> {
                        cacheFacade.insertInBackground(joinPoint, extract.nylon, extract.cacheKey)
                        cacheValue.value
                    }
                    is NylonState.Good -> {
                        cacheValue.value
                    }
                }
            }
            is NylonJoinPointExtract.InValidExtract -> {
                (joinPoint.signature as MethodSignature).also {
                    logger.error { "Error handling joinpoint for ${it.declaringTypeName}::${it.method.name}: ${extract.cause}" }
                }
            }
        }
}

