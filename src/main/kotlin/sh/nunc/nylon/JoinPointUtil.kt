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

    private fun ProceedingJoinPoint.getNylon(): Nylon {
       return(this.signature as MethodSignature).let {
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

    fun extract(joinPoint: ProceedingJoinPoint) : Pair<Nylon, String> =
        joinPoint.getNylon().let {
            Pair(it, getCacheKeyFromAnnotationKeyValue(getContextContainingArguments(joinPoint), it.key))
        }


}