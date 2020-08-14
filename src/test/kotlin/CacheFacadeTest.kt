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
import sh.nunc.nylon.CacheFacade
import sh.nunc.nylon.Nylon
import sh.nunc.nylon.NylonCacheValue
import java.time.Clock
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

@DisplayName("test if cache facade works")
class CacheFacadeTest {

    private val cacheName = "cacheName"
    private val cacheKey = "key"

    @MockK
    lateinit var cacheManager: CacheManager
    @MockK
    lateinit var nylon: Nylon
    @MockK
    lateinit var clock: Clock
    @MockK
    lateinit var valueCache: Cache
    @MockK
    lateinit var timeCache: Cache
    @InjectMockKs
    lateinit var cacheFacade: CacheFacade

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        every { cacheManager.getCache(cacheName) } returns valueCache
        every { cacheManager.getCache("${cacheName}__NYLON_T") } returns timeCache
        every { nylon.cacheName } returns cacheName
        every { nylon.timeoutMillis } returns 1000L
    }

    @DisplayName("check whether facade provides values from underlying manager")
    @Test
    fun testGetFromCacheOk() {
        val cachedValue = Cache.ValueWrapper { "cached" }
        val cachedTime = Cache.ValueWrapper { 1000L }

        every { valueCache[cacheKey] } returns cachedValue
        every { timeCache[cacheKey] } returns cachedTime

        cacheFacade.getFromCache(cacheName, cacheKey).let {
            Assertions.assertEquals(
                NylonCacheValue.Found(
                    cachedValue.get()!!,
                    cachedTime.get() as Long
                ), it
            )
        }

        verifyAll {
            valueCache[cacheKey]
            timeCache[cacheKey]
        }
    }

    @DisplayName("if insertion time misses, result is NylonCacheValue.Missing")
    @Test
    fun testGetFromCacheNoTime() {
        val cachedValue = Cache.ValueWrapper { "cached" }
        every { valueCache[cacheKey] } returns cachedValue
        every { timeCache[cacheKey] } returns null

        cacheFacade.getFromCache(cacheName, cacheKey).let {
            Assertions.assertEquals(
                NylonCacheValue.Missing,
                it
            )
        }

        verifyAll {
            valueCache[cacheKey]
            timeCache[cacheKey]
        }
    }

    @DisplayName("if no value is cached, result is NylonCacheValue.Missing")
    @Test
    fun testGetFromCacheNoValue() {
        val t = System.currentTimeMillis()
        val cachedTime = Cache.ValueWrapper { t }
        every { valueCache[cacheKey] } returns null
        every { timeCache[cacheKey] } returns cachedTime

        cacheFacade.getFromCache(cacheName, cacheKey).let {
            Assertions.assertEquals(
                NylonCacheValue.Missing,
                it
            )
        }

        verifyAll {
            cacheManager.getCache(cacheName)
            valueCache[cacheKey]
        }
    }

    @DisplayName("check that insertNow runs immediately")
    @Test
    fun testInsertNow(){
        val joinPoint = mockk<ProceedingJoinPoint>()
        val newValue = "new"

        every { clock.millis() } returns System.currentTimeMillis()
        every { joinPoint.proceed() } returns newValue
        justRun {
            valueCache.put(cacheKey, newValue)
        }
        justRun {
            timeCache.put(cacheKey, any())
        }
        cacheFacade.insertNow(joinPoint, nylon, cacheKey).let {
            Assertions.assertEquals(newValue, it)
        }

        verifyAll {
            joinPoint.proceed()
            valueCache.put(cacheKey, newValue)
            timeCache.put(cacheKey, any())
        }

    }

    @DisplayName("test whether update in background fills underlying cahces correct")
    @Test
    fun testInsertBackground(){
        val joinPoint = mockk<ProceedingJoinPoint>()
        val newValue = "new"
        val t = 10000L

        every { joinPoint.proceed() } returns newValue
        every { clock.millis() } returns t
        justRun {
            valueCache.put(cacheKey, newValue)
        }
        justRun {
            timeCache.put(cacheKey, t)
        }
        cacheFacade.insertInBackground(joinPoint, nylon, cacheKey)
        val pool = ForkJoinPool.commonPool()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
        verifyAll {
            joinPoint.proceed()
            valueCache.put(cacheKey, newValue)
            timeCache.put(cacheKey, t)
        }
    }
}