package sh.nunc.nylon

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.CodeSignature
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component

@Component
class JoinPointUtil {

    private val expressionParser =
        SpelExpressionParser()

    private fun ProceedingJoinPoint.getNylon(): Nylon =
       (signature as MethodSignature).let {
            it.method.annotations.filterIsInstance<Nylon>().firstOrNull()!!
       }

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

    fun extract(joinPoint: ProceedingJoinPoint) : Pair<Nylon, String> =
        joinPoint.getNylon().let {
            Pair(it, getCacheKey(joinPoint.getEvaluationContext(), it.key))
        }


}