package io.github.dornol.idkit.validation

import io.github.dornol.idkit.ulid.UlidParser
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * Jakarta Bean Validation constraint for ULID strings.
 *
 * Delegates the format check to [UlidParser.isValid]: the value must be a 26-character
 * Crockford Base32 encoded ULID. `null` values are treated as valid (use `@NotNull` alongside
 * to reject them).
 *
 * Applicable to `String` / `CharSequence` properties.
 *
 * ### Usage
 *
 * ```kotlin
 * data class CreateOrderRequest(
 *     @field:ValidUlid val orderId: String,
 * )
 * ```
 *
 * The `jakarta.validation-api` dependency is declared as `compileOnly` on the idkit artifact,
 * so users who do not wire a validation engine on their classpath pay no transitive cost.
 *
 * @since 2.3.0
 */
@MustBeDocumented
@Constraint(validatedBy = [UlidValidator::class])
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class ValidUlid(
    val message: String = "must be a valid ULID",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/**
 * Validator backing [ValidUlid]. `null` is accepted (compose with `@NotNull` to reject).
 */
class UlidValidator : ConstraintValidator<ValidUlid, CharSequence> {
    override fun isValid(value: CharSequence?, context: ConstraintValidatorContext?): Boolean =
        value == null || UlidParser.isValid(value.toString())
}
