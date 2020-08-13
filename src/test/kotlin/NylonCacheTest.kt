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

    private val key = "key"
    private val cacheName = "cache"
    private val nylonValue = NylonCacheValue.Found("cached", 0L)

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        every { joinPointUtil.extract(joinPoint)} returns Pair(annotation, key)
        every { annotation.key } returns key
        every { annotation.cacheName } returns cacheName
        every { annotation.timeoutMillis } returns 100
    }

    @MockK lateinit var cacheFacade: CacheFacade
    @MockK lateinit var nylonCacheChecker: NylonCacheChecker
    @MockK lateinit var joinPointUtil: JoinPointUtil
    @MockK lateinit var joinPoint: ProceedingJoinPoint
    @MockK lateinit var annotation: Nylon
    @InjectMockKs lateinit var nylonAspect: NylonAspectRedis

    @Test
    @DisplayName("check whether old items update in background")
    fun testBackgroundFetch(){
        every { cacheFacade.getFromCache(cacheName, key) } returns nylonValue
        every { nylonCacheChecker.check(annotation, nylonValue) } returns NylonState.RefreshInBackGround(nylonValue.value)
        justRun { cacheFacade.updateInBackground(joinPoint, cacheName, key, nylonValue.value) }

        nylonAspect.nylonCache(joinPoint).let {
            Assertions.assertEquals(nylonValue.value, it)
        }
        verifyAll {
            cacheFacade.getFromCache(cacheName, key)
            cacheFacade.updateInBackground(joinPoint, cacheName, key, nylonValue.value)
        }
    }


    @Test
    @DisplayName("check whether OK items are directly returned")
    fun testReturnDirect(){
        every { cacheFacade.getFromCache(cacheName, key) } returns nylonValue
        every { nylonCacheChecker.check(annotation, nylonValue) } returns NylonState.Good(nylonValue.value)

        nylonAspect.nylonCache(joinPoint).let {
            Assertions.assertEquals(nylonValue.value, it)
        }

        verifyAll { cacheFacade.getFromCache(cacheName, key) }
    }

    @Test
    @DisplayName("check whether failed items are fetched immediately")
    fun testFetchCheckFailed(){
        val newVal = "NEW"
        every { cacheFacade.getFromCache(cacheName, key) } returns nylonValue
        every { nylonCacheChecker.check(annotation, nylonValue) } returns NylonState.FetchNow
        every { cacheFacade.insertNow(joinPoint, cacheName, key, any()) } returns newVal

        nylonAspect.nylonCache(joinPoint).let {
            Assertions.assertEquals(newVal, it)
        }

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