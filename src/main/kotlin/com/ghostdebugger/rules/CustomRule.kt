package com.ghostdebugger.rules

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.ghostdebugger.fix.engine.FixPlan
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object RuleSeveritySerializer : KSerializer<RuleSeverity> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RuleSeverity", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: RuleSeverity) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): RuleSeverity {
        val str = decoder.decodeString()
        return RuleSeverity.entries.firstOrNull { it.name.equals(str, ignoreCase = true) } ?: RuleSeverity.WARNING
    }
}

@Serializable(with = RuleSeveritySerializer::class)
enum class RuleSeverity { ERROR, WARNING, WEAK_WARNING, INFO }

@Serializable
data class RuleMatch(
    val element: String = "",
    val `name-matches`: String? = null,
    val `text-matches`: String? = null,
    val `parameter-type`: String? = null,
    val `receiver-type`: String? = null,
    val `argument-type`: String? = null,
    val inside: String? = null,
    val `annotated-with`: String? = null,
    val `contains-text`: String? = null,
    val unless: RuleMatch? = null
)

@Serializable
data class CustomRule(
    val id: String,
    val language: String,
    val severity: RuleSeverity,
    val message: String,
    val match: RuleMatch,
    val fix: FixPlan? = null
)

@Serializable
data class CustomRuleFile(val version: Int, val rules: List<CustomRule>)

object CustomRuleCodec {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    fun decode(raw: String): CustomRuleFile? =
        runCatching { yaml.decodeFromString(CustomRuleFile.serializer(), raw) }.getOrNull()
}
