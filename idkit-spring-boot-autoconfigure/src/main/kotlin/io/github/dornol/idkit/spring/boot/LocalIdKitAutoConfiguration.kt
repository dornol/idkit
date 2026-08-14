package io.github.dornol.idkit.spring.boot

import io.github.dornol.idkit.IdGenerator
import io.github.dornol.idkit.nanoid.NanoIdGenerator
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** Auto-configures generators that do not require a distributed worker lease. */
@AutoConfiguration
@ConditionalOnClass(IdGenerator::class)
@EnableConfigurationProperties(IdKitProperties::class)
class LocalIdKitAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "idkit.generator", name = ["type"], havingValue = "UUID_V7")
    @ConditionalOnMissingBean(IdGenerator::class)
    fun uuidV7IdGenerator(): IdGenerator<java.util.UUID> = UuidV7IdGenerator()

    @Bean
    @ConditionalOnProperty(prefix = "idkit.generator", name = ["type"], havingValue = "ULID")
    @ConditionalOnMissingBean(IdGenerator::class)
    fun ulidIdGenerator(): IdGenerator<String> = UlidIdGenerator()

    @Bean
    @ConditionalOnProperty(prefix = "idkit.generator", name = ["type"], havingValue = "NANOID")
    @ConditionalOnMissingBean(IdGenerator::class)
    fun nanoIdGenerator(properties: IdKitProperties): IdGenerator<String> =
        NanoIdGenerator(properties.generator.nanoSize, properties.generator.nanoAlphabet)
}
