package io.github.dornol.idkit.validation

import io.github.dornol.idkit.uuidv7.UuidV7Parser
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Jakarta Bean Validation constraint for UUID v7 values.
 *
 * Applies to both [UUID] properties and textual representations (`String` / `CharSequence`):
 *  - [UUID]: the value's `version()` must be `7`.
 *  - `CharSequence`: the value must parse via [UUID.fromString] and the parsed `version()`
 *    must be `7`.
 *
 * `null` values are treated as valid (compose with `@NotNull` to reject them).
 *
 * ### Usage
 *
 * ```kotlin
 * data class CreateAccountRequest(
 *     @field:ValidUuidV7 val accountId: UUID,
 *     @field:ValidUuidV7 val correlationId: String?,
 * )
 * ```
 *
 * The `jakarta.validation-api` dependency is declared as `compileOnly` on the idkit artifact,
 * so users who do not wire a validation engine on their classpath pay no transitive cost.
 *
 * @since 2.3.0
 */
@MustBeDocumented
@Constraint(validatedBy = [UuidV7UuidValidator::class, UuidV7StringValidator::class])
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class ValidUuidV7(
    val message: String = "must be a valid UUID v7",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/** Validator applied when the annotated property is a [UUID]. */
class UuidV7UuidValidator : ConstraintValidator<ValidUuidV7, UUID> {
    override fun isValid(value: UUID?, context: ConstraintValidatorContext?): Boolean =
        value == null || UuidV7Parser.isValid(value)
}

/** Validator applied when the annotated property is a `String` / `CharSequence`. */
class UuidV7StringValidator : ConstraintValidator<ValidUuidV7, CharSequence> {
    override fun isValid(value: CharSequence?, context: ConstraintValidatorContext?): Boolean =
        value == null || UuidV7Parser.isValid(value)
}
