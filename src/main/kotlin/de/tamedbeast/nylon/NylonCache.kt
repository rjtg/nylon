package de.tamedbeast.nylon

import mu.KotlinLogging
import net.jodah.failsafe.Failsafe
import net.jodah.failsafe.Fallback
import net.jodah.failsafe.Timeout
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Pointcut
import org.aspectj.lang.reflect.CodeSignature
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.cache.RedisCache
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component
import javax.annotation.PostConstruct
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Nylon(val key: String, val softTtlMillis: Long, val hardTtlMillis: Long, val timeoutMillis: Long, val cacheName: String)

enum class NylonState {
    GOOD, SOFT, HARD
}

@Component
class NylonAspect(@Autowired private val cacheManager: RedisCacheManager) {


    private val expressionParser = SpelExpressionParser();

    @PostConstruct
    fun postConstruct() {
       if (!cacheManager.canCreateNewCaches()) logger.warn { "Redis CacheManager must be configured to allow creation of caches on the fly" }
    }

    @Pointcut("@annotation(ResilenceCachable)")
    fun nylonPointcut(nylon: Nylon) {};

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
        return expression.getValue(context) as String
    }


    @ExperimentalTime
    @Around("TwoLayerRedisCacheablePointcut(twoLayerRedisCacheable)")
    @Throws(Throwable::class)
    fun nylonCache(joinPoint: ProceedingJoinPoint, nylon: Nylon): Any? {
        val context = getContextContainingArguments(joinPoint)
        val cacheKey = getCacheKeyFromAnnotationKeyValue(context, nylon.key)

        val start = System.currentTimeMillis()

        val cache = cacheManager.getCache(nylon.cacheName)
        return cache?.let { valueCache ->
            if (valueCache is RedisCache) {
                val cacheValue = valueCache[cacheKey]?.get()?.let {c ->
                    val inserTionTime = getInsertionTimeCache(nylon)?.let {
                        tCache ->
                        tCache[cacheKey]?.get()?.let { time ->
                            if (time is Long) {
                                time
                            } else {
                                //insertion time unknown - set now
                                tCache.putIfAbsent(cacheKey, start)
                                start
                            }
                        }
                    } ?: start
                    val state = when {
                        (start - inserTionTime > nylon.hardTtlMillis) -> {
                            //value is old and should only be used if refresh takes to long
                            NylonState.HARD
                        }
                        (start - inserTionTime > nylon.softTtlMillis) -> {
                            //value is good but needs background refresh
                            NylonState.SOFT
                        }
                        else -> {
                            //value is good and will not be refreshed
                            NylonState.GOOD
                        }

                    }
                    Pair(c, state)
                }
                cacheValue?.let {
                    when(it.second) {
                        NylonState.HARD -> {
                            val timeout: Timeout<Any?> = Timeout.of(nylon.timeoutMillis.milliseconds.toJavaDuration())
                            val fallback: Fallback<Any?> = Fallback.of(it.first)
                            Failsafe.with(fallback, timeout).get { _ -> insert(joinPoint, nylon)}
                        }
                        NylonState.SOFT -> {
                            val fallback: Fallback<Any?> = Fallback.of(it.first)
                            Failsafe.with(fallback).getAsync { _ -> insert(joinPoint, nylon)}
                            it.first
                        }
                        NylonState.GOOD -> it.first
                    }

                } ?: insert(joinPoint, nylon)
            }

        }

    }

    private fun getInsertionTimeCache(nylon: Nylon) =
        cacheManager.getCache("${nylon.cacheName}__NYLON_T")


    private fun insert(joinPoint: ProceedingJoinPoint, nylon: Nylon): Any? {

        val result = joinPoint.proceed()

        val context = getContextContainingArguments(joinPoint)
        val cacheKey = getCacheKeyFromAnnotationKeyValue(context, nylon.key)

        val cache = cacheManager.getCache(nylon.cacheName)
        cache?.let { realCache ->
            realCache.put(cacheKey, result)
            getInsertionTimeCache(nylon).put(cacheKey, System.currentTimeMillis())
        }
        return result
    }
}

private fun RedisCacheManager.canCreateNewCaches():Boolean {
    return javaClass.getDeclaredField("allowInFlightCacheCreation").let {
        it.isAccessible = true
        return@let it.getBoolean(this);
    }
}