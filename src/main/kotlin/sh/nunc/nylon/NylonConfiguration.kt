package sh.nunc.nylon

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class NylonConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun getSystemUtcClock(): Clock = Clock.systemUTC()
}