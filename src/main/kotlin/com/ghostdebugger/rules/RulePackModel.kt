package com.ghostdebugger.rules

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable

@Serializable
data class RulePack(
    val id: String,
    val name: String,
    val description: String = "",
    val enabledByDefault: Boolean = true,
    val rules: List<CustomRule> = emptyList()
)

object RulePackCodec {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    fun decode(raw: String): RulePack? =
        runCatching { yaml.decodeFromString(RulePack.serializer(), raw) }.getOrNull()
}
