package sh.nunc.nylon

import mu.KotlinLogging
import net.jodah.failsafe.Failsafe
import net.jodah.failsafe.Fallback
import net.jodah.failsafe.Timeout
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.aspectj.lang.reflect.CodeSignature
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Nylon(val key: String, val softTtlMillis: Long, val timeoutMillis: Long, val cacheName: String, val jitter: Long = 0)

private sealed class NylonState {
    data class Good(val value: Any) : NylonState()
    data class RefreshInBackGround(val value: Any) : NylonState()
    object FetchNow : NylonState()
}


@Component
@Aspect
class NylonAspectRedis(@Autowired private val joinPointUtil: JoinPointUtil, @Autowired private val cacheFacade: CacheFacade, @Autowired private val clock: Clock) {



    @Pointcut("@annotation(sh.nunc.nylon.Nylon)")
    fun nylonPointcut() {
        //just a pointcut
    }

    @Around("nylonPointcut()")
    @Throws(Throwable::class)
    fun nylonCache(joinPoint: ProceedingJoinPoint): Any? {
        val (nylon, cacheKey) = joinPointUtil.extract(joinPoint)
        val start = clock.millis()

        val cacheValue = cacheFacade.getFromCache(nylon.cacheName, cacheKey)?.let {(v, t) ->
            if (start - t <= nylon.softTtlMillis + if (nylon.jitter > 0) Random.nextLong(-nylon.jitter, nylon.jitter) else 0) {
                NylonState.Good(v)
            } else {
                NylonState.RefreshInBackGround(v)
            }
        }?: NylonState.FetchNow

        return when(cacheValue) {
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

@Component
class CacheFacade(@Autowired private val cacheManager: CacheManager, @Autowired private val clock: Clock) {

    fun getFromCache(cacheName:String, key:String): Pair<Any, Long>? {
        return getCacheValue(cacheName, key)?.let {v ->
            getInsertionTimeForCacheValue(cacheName, key)?.let {t->
                Pair(v,t)
            }
        }
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
        val timeout: Timeout<Any?> = Timeout.of(Duration.ofMillis(timeoutMillis))
        val fallback: Fallback<Any?> = Fallback.of { null}
        return Failsafe.with(fallback, timeout).get { _ -> insert(joinPoint, cacheName, cacheKey)}
    }

    fun updateInBackground(joinPoint: ProceedingJoinPoint, cacheName: String, cacheKey: String, oldValue: Any) {
        val fallback: Fallback<Any> = Fallback.of(oldValue)
        Failsafe.with(fallback).getAsync { _ -> insert(joinPoint, cacheName, cacheKey)}
    }
}

@Component
class JoinPointUtil {

    private val expressionParser = SpelExpressionParser()

    private fun getNylon(joinPoint: ProceedingJoinPoint): Nylon {
       return(joinPoint.signature as MethodSignature).let {
            it.method.annotations.filterIsInstance<Nylon>().firstOrNull()!!
       }
    }

    private fun getContextContainingArguments(joinPoint: ProceedingJoinPoint): StandardEvaluationContext {
        val context = StandardEvaluationContext()

        val codeSignature = joinPoint.signature as CodeSignature
        val parameterNames = codeSignature.parameterNames
        val args = joinPoint.args

        for (i in parameterNames.indices) {
            context.setVariable(parameterNames[i], args[i])
        }
        return context
    }

    private fun getCacheKeyFromAnnotationKeyValue(context: StandardEvaluationContext, key: String): String {
        val expression = expressionParser.parseExpression(key)
        return (expression.getValue(context) as List<*>).joinToString(",","[", "]")
    }

    fun extract(joinPoint: ProceedingJoinPoint) : Pair<Nylon, String> {
        val nylon = getNylon(joinPoint)
        return Pair(nylon, getCacheKeyFromAnnotationKeyValue(getContextContainingArguments(joinPoint), nylon.key))
    }


}

@Configuration
class NylonConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun getSystemUtcClock(){
        Clock.systemUTC()
    }
}
