package sh.nunc.nylon

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Clock
import kotlin.random.Random

@Component
class NylonCacheChecker(@Autowired private val clock: Clock) {

    fun check(nylon: Nylon, cacheValue: NylonCacheValue) : NylonState =
        when(cacheValue){
            is NylonCacheValue.Found -> checkFound(nylon, cacheValue)
            NylonCacheValue.Missing -> NylonState.FetchNow
        }

    private fun jitter(nylon: Nylon): Long =
        if (nylon.jitter > 0) Random.nextLong(
            -nylon.jitter,
            nylon.jitter
        ) else 0


    private fun checkFound(nylon: Nylon, cacheValue: NylonCacheValue.Found) : NylonState =
        if (clock.millis() - cacheValue.insertionTime + jitter(nylon) <= nylon.softTtlMillis) {
            NylonState.Good(cacheValue.value)
        } else {
            NylonState.RefreshInBackground(cacheValue.value)
        }


}