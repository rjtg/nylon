import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import org.aspectj.lang.ProceedingJoinPoint
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import sh.nunc.nylon.*
import java.time.Clock
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

@DisplayName("Basic tests for the Nylon Cache")
class NylonCacheTest {

    @BeforeEach
    fun setUp() = MockKAnnotations.init(this)

    @MockK lateinit var cacheManager: CacheManager
    @MockK lateinit var cacheFacade: CacheFacade
    @MockK lateinit var clock: Clock
    @MockK lateinit var joinPointUtil: JoinPointUtil
    @InjectMockKs lateinit var nylonAspect: NylonAspectRedis

    @Test
    @DisplayName("check whether mockobjects are initialized correct")
    fun testCorrectInitialization(){
        Assertions.assertNotNull(nylonAspect)
        Assertions.assertNotNull(nylonAspect.run {
            cacheManager
        })
    }

    @Test
    @DisplayName("check whether old items update in background")
    fun testBackgroundFetch(){
        val annotation = mockk<Nylon>()
        val joinPoint = mockk<ProceedingJoinPoint>()
        val key = "key"
        val ttl = 100L
        val t = 0L
        every { joinPointUtil.extract(joinPoint)} returns Pair(annotation, key)
        every { annotation.key } returns key
        val cacheName = "cache"
        every { annotation.cacheName } returns cacheName
        every { annotation.jitter } returns 0
        every { annotation.softTtlMillis } returns ttl
        every { annotation.timeoutMillis } returns 100
        every { clock.millis() } returns ttl + t + 1L
        val cached = "cachedValue"
        every { cacheFacade.getFromCache(cacheName, key) } returns Pair(cached, t)
        justRun { cacheFacade.updateInBackground(joinPoint, cacheName, key, cached) }

        val ret = nylonAspect.nylonCache(joinPoint)
        Assertions.assertEquals(cached, ret)
        verifyAll {
            cacheFacade.getFromCache(cacheName, key)
            cacheFacade.updateInBackground(joinPoint, cacheName, key, cached)
        }
    }


    @Test
    @DisplayName("check whether current items are directly returned")
    fun testReturnDirect(){
        val annotation = mockk<Nylon>()
        val joinPoint = mockk<ProceedingJoinPoint>()
        val key = "key"
        val ttl = 100L
        val t = 0L
        every { joinPointUtil.extract(joinPoint)} returns Pair(annotation, key)
        every { annotation.key } returns key
        val cacheName = "cache"
        every { annotation.cacheName } returns cacheName
        every { annotation.jitter } returns 0
        every { annotation.softTtlMillis } returns ttl
        every { annotation.timeoutMillis } returns 100
        every { clock.millis() } returns ttl + t
        val cached = "cachedValue"
        every { cacheFacade.getFromCache(cacheName, key) } returns Pair(cached, t)

        val ret = nylonAspect.nylonCache(joinPoint)
        Assertions.assertEquals(cached, ret)

        verifyAll { cacheFacade.getFromCache(cacheName, key) }
    }

    @Test
    @DisplayName("check whether missing items are directly fetched")
    fun testFetchDirect(){
        val annotation = mockk<Nylon>()
        val joinPoint = mockk<ProceedingJoinPoint>()
        val key = "key"
        val ttl = 100L
        val t = 0L
        every { joinPointUtil.extract(joinPoint)} returns Pair(annotation, key)
        every { annotation.key } returns key
        val cacheName = "cache"
        every { annotation.cacheName } returns cacheName
        every { annotation.jitter } returns 0
        every { annotation.softTtlMillis } returns ttl
        every { annotation.timeoutMillis } returns 100
        every { clock.millis() } returns ttl + t + 1L
        val cached = "cachedValue"
        every { cacheFacade.getFromCache(cacheName, key) } returns null
        every { cacheFacade.insertNow(joinPoint, cacheName, key, any()) } returns cached

        val ret = nylonAspect.nylonCache(joinPoint)
        Assertions.assertEquals(cached, ret)

        verifyAll {
            cacheFacade.getFromCache(cacheName, key)
            cacheFacade.insertNow(joinPoint, cacheName, key, any())
        }
    }

}

@DisplayName("test if cache facade works")
class CacheFacadeTest {
    @BeforeEach
    fun setUp() = MockKAnnotations.init(this)

    @MockK lateinit var cacheManager: CacheManager
    @MockK lateinit var clock: Clock
    @InjectMockKs lateinit var cacheFacade: CacheFacade

    @DisplayName("check whether facade provides values from underlying manager")
    @Test
    fun testGetFromCacheOk() {
        val valueCache = mockk<Cache>()
        val timeCache = mockk<Cache>()
        val cacheName = "cacheName"
        val cacheKey = "key"
        val cachedValue = Cache.ValueWrapper { "cached" }
        val t = 1000L
        val cachedTime = Cache.ValueWrapper { t }
        every { cacheManager.getCache(cacheName) } returns valueCache
        every { cacheManager.getCache("${cacheName}__NYLON_T") } returns timeCache
        every { valueCache[cacheKey] } returns cachedValue
        every { timeCache[cacheKey] } returns cachedTime
        every { clock.millis() } returns t
        Assertions.assertEquals(Pair(cachedValue.get(), cachedTime.get()), cacheFacade.getFromCache(cacheName, cacheKey))
        verifyAll {
            cacheManager.getCache(cacheName)
            cacheManager.getCache("${cacheName}__NYLON_T")
            valueCache[cacheKey]
            timeCache[cacheKey]
        }
    }

    @DisplayName("check whether missing time information does not respond with a value")
    @Test
    fun testGetFromCacheNoTime() {
        val valueCache = mockk<Cache>()
        val timeCache = mockk<Cache>()
        val cacheName = "cacheName"
        val cacheKey = "key"
        val cachedValue = Cache.ValueWrapper { "cached" }
        every { cacheManager.getCache(cacheName) } returns valueCache
        every { cacheManager.getCache("${cacheName}__NYLON_T") } returns timeCache
        every { valueCache[cacheKey] } returns cachedValue
        every { timeCache[cacheKey] } returns null
        Assertions.assertEquals(null, cacheFacade.getFromCache(cacheName, cacheKey))
        verifyAll {
            cacheManager.getCache(cacheName)
            cacheManager.getCache("${cacheName}__NYLON_T")
            valueCache[cacheKey]
            timeCache[cacheKey]
        }
    }

    @DisplayName("check whether missing value does not leas to a response")
    @Test
    fun testGetFromCacheNoValue() {
        val valueCache = mockk<Cache>()
        val timeCache = mockk<Cache>()
        val cacheName = "cacheName"
        val cacheKey = "key"
        val t = System.currentTimeMillis()
        val cachedTime = Cache.ValueWrapper { t }
        every { cacheManager.getCache(cacheName) } returns valueCache
        every { cacheManager.getCache("${cacheName}__NYLON_T") } returns timeCache
        every { valueCache[cacheKey] } returns null
        every { timeCache[cacheKey] } returns cachedTime
        Assertions.assertEquals(null, cacheFacade.getFromCache(cacheName, cacheKey))
        verifyAll {
            cacheManager.getCache(cacheName)
            valueCache[cacheKey]
        }
    }

    @DisplayName("check that insertNow runs immediately")
    @Test
    fun testInsertNow(){
        val joinPoint = mockk<ProceedingJoinPoint>()
        val valueCache = mockk<Cache>()
        val timeCache = mockk<Cache>()
        val cacheName = "cacheName"
        val cacheKey = "key"
        val newValue = "new"

        every { clock.millis() } returns System.currentTimeMillis()
        every { cacheManager.getCache(cacheName) } returns valueCache
        every { cacheManager.getCache("${cacheName}__NYLON_T") } returns timeCache
        every { joinPoint.proceed() } returns newValue
        justRun {
            valueCache.put(cacheKey, newValue)
        }
        justRun {
            timeCache.put(cacheKey, any())
        }
        val retVal = cacheFacade.insertNow(joinPoint, cacheName, cacheKey, 2000)

        verifyAll {
            joinPoint.proceed()
            cacheManager.getCache(cacheName)
            cacheManager.getCache("${cacheName}__NYLON_T")
            valueCache.put(cacheKey, newValue)
            timeCache.put(cacheKey, any())
        }
        Assertions.assertEquals(newValue, retVal)
    }

    @DisplayName("test whether update in background fills underlying cahces correct")
    @Test
    fun testInsertBackground(){
        val joinPoint = mockk<ProceedingJoinPoint>()
        val valueCache = mockk<Cache>()
        val timeCache = mockk<Cache>()
        val cacheName = "cacheName"
        val cacheKey = "key"
        val newValue = "new"
        val t = 10000L

        every { cacheManager.getCache(cacheName) } returns valueCache
        every { cacheManager.getCache("${cacheName}__NYLON_T") } returns timeCache
        every { joinPoint.proceed() } returns newValue
        every { clock.millis() } returns t
        justRun {
            valueCache.put(cacheKey, newValue)
        }
        justRun {
            timeCache.put(cacheKey, t)
        }
        cacheFacade.updateInBackground(joinPoint, cacheName, cacheKey, "old")
        val pool = ForkJoinPool.commonPool()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
        verifyAll {
            joinPoint.proceed()
            cacheManager.getCache(cacheName)
            cacheManager.getCache("${cacheName}__NYLON_T")
            valueCache.put(cacheKey, newValue)
            timeCache.put(cacheKey, t)
        }
    }
}