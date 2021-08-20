package sh.nunc.nylon

import mu.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.CodeSignature
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component
import java.lang.RuntimeException

private val logger = KotlinLogging.logger {}


sealed class NylonJoinPointExtract {
    data class ValidExtract(val nylon:Nylon, val cacheKey: String): NylonJoinPointExtract()
    data class InValidExtract(val cause: Throwable) : NylonJoinPointExtract()
}

@Component
class JoinPointUtil {

    private val expressionParser =
        SpelExpressionParser()

    private fun ProceedingJoinPoint.getNylon(): Nylon =
        (signature as MethodSignature).method.getAnnotation<Nylon>(Nylon::class.java)

    private fun ProceedingJoinPoint.getEvaluationContext(): StandardEvaluationContext =
        StandardEvaluationContext().also {ctx ->
            (signature as CodeSignature).let {sig ->
                sig.parameterNames.zip(args).forEach {(name, arg) -> ctx.setVariable(name, arg) }
            }
        }

    private fun getCacheKey(context: StandardEvaluationContext, key: String): String =
        expressionParser.parseExpression(key).let {
            (it.getValue(context) as List<*>).joinToString(",","[", "]")
        }

    fun extract(joinPoint: ProceedingJoinPoint) : NylonJoinPointExtract =
        try {
            logger.debug { "extracting from ${joinPoint.signature.name}" }
            joinPoint.getNylon().let{
                NylonJoinPointExtract.ValidExtract(it, getCacheKey(joinPoint.getEvaluationContext(), it.key))
            }
        } catch (cause: RuntimeException) {
            NylonJoinPointExtract.InValidExtract(cause)
        }


}