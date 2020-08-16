import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import sh.nunc.nylon.JoinPointUtil
import sh.nunc.nylon.Nylon
import sh.nunc.nylon.NylonJoinPointExtract
import java.lang.reflect.Method

class JoinPointUtilTest {

    abstract class SignatureMock: MethodSignature {
        override fun getMethod(): Method {
            return MethodMock.getMethod()
        }

    }
    private class MethodMock {
        companion object {
            fun getMethod(): Method = MethodMock::class.java.getDeclaredMethod("method")
            fun getAnnotation(): Nylon = getMethod().getAnnotation<Nylon>(Nylon::class.java)
        }
        @Nylon("{#a}", 100L,100L,"cache")
        fun method(){}

    }

    @InjectMockKs
    private lateinit var joinpointutil: JoinPointUtil

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
    }

    @Test
    @DisplayName("basic extraction test")
    fun testExtract(){
        val jp = mockk<ProceedingJoinPoint>()
        val sig = spyk<SignatureMock>()
        every { jp.signature } returns sig
        every { jp.args } returns arrayOf("KEYNAME")
        every { sig.parameterNames } returns arrayOf("a")

        joinpointutil.extract(jp).let {
            Assertions.assertEquals(NylonJoinPointExtract.ValidExtract(MethodMock.getAnnotation(),"[KEYNAME]"), it)
        }

    }
}