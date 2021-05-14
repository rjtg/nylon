import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import org.aspectj.lang.ProceedingJoinPoint
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import sh.nunc.nylon.*

@DisplayName("Basic tests for the Nylon Cache")
class NylonCacheTest {

    private val key = "key"
    private val cacheName = "cache"
    private val nylonValue = NylonCacheValue.Found("cached", 0L)

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        every { joinPointUtil.extract(joinPoint)} returns NylonJoinPointExtract.ValidExtract(annotation, key)
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
    @DisplayName("if checker decides to do backgroundrefresh cachefacade does backgroundrefresh")
    fun testBackgroundRefresh(){
        every { cacheFacade.getFromCache(cacheName, key) } returns nylonValue
        every { nylonCacheChecker.check(annotation, nylonValue) } returns NylonState.RefreshInBackground(nylonValue.value)
        justRun { cacheFacade.insertInBackground(joinPoint, annotation, key) }

        nylonAspect.nylonCache(joinPoint).let {
            Assertions.assertEquals(nylonValue.value, it)
        }
        verifyAll {
            cacheFacade.getFromCache(cacheName, key)
            cacheFacade.insertInBackground(joinPoint, annotation, key)
        }
    }


    @Test
    @DisplayName("if old value passes check, nothing is done")
    fun testReturnDirect(){
        every { cacheFacade.getFromCache(cacheName, key) } returns nylonValue
        every { nylonCacheChecker.check(annotation, nylonValue) } returns NylonState.Good(nylonValue.value)

        nylonAspect.nylonCache(joinPoint).let {
            Assertions.assertEquals(nylonValue.value, it)
        }

        verifyAll { cacheFacade.getFromCache(cacheName, key) }
    }

    @Test
    @DisplayName("if old value fails check, new item is fetched and immediately put into cache")
    fun testFetchCheckFailed(){
        val newVal = "NEW"
        every { cacheFacade.getFromCache(cacheName, key) } returns nylonValue
        every { nylonCacheChecker.check(annotation, nylonValue) } returns NylonState.FetchNow
        every { cacheFacade.insertNow(joinPoint, annotation, key) } returns newVal

        nylonAspect.nylonCache(joinPoint).let {
            Assertions.assertEquals(newVal, it)
        }

        verifyAll {
            cacheFacade.getFromCache(cacheName, key)
            cacheFacade.insertNow(joinPoint, annotation, key)
        }
    }

}

