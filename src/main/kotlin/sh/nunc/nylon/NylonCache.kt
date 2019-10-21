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
import org.springframework.cache.CacheManager
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Nylon(val key: String, val softTtlMillis: Long, val timeoutMillis: Long, val cacheName: String, val jitter: Long = 0)

private enum class NylonState {
    GOOD, BACKGROUND_REFRESH, FETCH
}

@Component
@Aspect
class NylonAspectRedis(@Autowired private val cacheManager: CacheManager) {


    private val expressionParser = SpelExpressionParser()

    @Pointcut("@annotation(sh.nunc.nylon.Nylon)")
    fun nylonPointcut() {
        //just a pointcut
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


    @Around("nylonPointcut()")
    @Throws(Throwable::class)
    fun nylonCache(joinPoint: ProceedingJoinPoint): Any? {
        val sig = joinPoint.signature as MethodSignature
        val nylon = sig.method.annotations.filterIsInstance<Nylon>().firstOrNull()!!
        val context = getContextContainingArguments(joinPoint)
        val cacheKey = getCacheKeyFromAnnotationKeyValue(context, nylon.key)

        val start = System.currentTimeMillis()

        val cacheValue = getCacheValue(nylon.cacheName, cacheKey)?.let {
            val time = getInsertionTimeForCacheValue(nylon.cacheName, cacheKey)
            val state = time?.let {t ->
                if (start - t <= nylon.softTtlMillis + if (nylon.jitter > 0) Random.nextLong(-nylon.jitter, nylon.jitter) else 0) {
                    NylonState.GOOD
                } else {
                    NylonState.BACKGROUND_REFRESH
                }
            } ?: NylonState.FETCH
            Pair(it, state)
        } ?: Pair(null, NylonState.FETCH)
        return when(cacheValue.second) {
            NylonState.FETCH -> {
                logger.debug { "fetching value downstream. Using Fallback: ${cacheValue.first != null}. Timeout: ${nylon.timeoutMillis} ms." }
                val timeout: Timeout<Any?> = Timeout.of(Duration.ofMillis(nylon.timeoutMillis))
                val fallback: Fallback<Any?> = Fallback.of(cacheValue.first)
                Failsafe.with(fallback, timeout).get { _ -> insert(joinPoint, nylon.cacheName, cacheKey)}
            }
            NylonState.BACKGROUND_REFRESH -> {
                logger.debug { "Using cached value. refreshing value in background." }
                val fallback: Fallback<Any?> = Fallback.of(cacheValue.first)
                Failsafe.with(fallback).getAsync { _ -> insert(joinPoint, nylon.cacheName, cacheKey)}
                cacheValue.first
            }
            NylonState.GOOD -> {
                logger.debug { "Using cached value." }
                cacheValue.first
            }
        }
    }

    private fun getCacheValue(cacheName: String, key: String): Any? {
        return try {
            val cache = cacheManager.getCache(cacheName)
            cache?.let { valueCache ->
                valueCache[key]?.get()
            }
        } catch (e: Exception){
            null
        }
    }

    private fun getInsertionTimeForCacheValue(cacheName: String, key: String) : Long? {
        return try {
            getCacheValue(getInsertionTimeCacheName(cacheName), key) as Long?
        } catch (e: Exception) {
            null
        }
    }

    private fun getInsertionTimeCacheName(cacheName: String) = "${cacheName}__NYLON_T"


    private fun insert(joinPoint: ProceedingJoinPoint, cacheName: String, cacheKey: String): Any? {

        val result = joinPoint.proceed()
        val time = System.currentTimeMillis()

        val cache = cacheManager.getCache(cacheName)
        val iCache = cacheManager.getCache(getInsertionTimeCacheName(cacheName))
        cache?.let { realCache ->
            realCache.put(cacheKey, result)
            iCache?.put(cacheKey, time)
        }
        return result
    }
}
