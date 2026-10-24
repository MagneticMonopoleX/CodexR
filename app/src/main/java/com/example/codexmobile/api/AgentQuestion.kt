package com.example.codexmobile.api
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AgentQuestion(val callId: String, val question: String, val options: List<String>) {
    companion object {
        fun parse(callId: String, arguments: String): AgentQuestion {
            try {
                val args = Json.parseToJsonElement(arguments).jsonObject
                require(args.keys == setOf("question", "options"))
                val question = args["question"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val options = (args["options"] as? JsonArray)?.map {
                    require(it.jsonPrimitive.isString)
                    it.jsonPrimitive.content.trim().also { value -> require(value.isNotEmpty() && value.length <= 200) }
                } ?: error("Missing options")
                require(args["question"]!!.jsonPrimitive.isString && question.isNotEmpty() && question.length <= 2_000)
                require(options.size <= 5 && options.distinct().size == options.size)
                return AgentQuestion(callId, question, options)
            } catch (error: Exception) {
                throw CodexProtocolException("Invalid clarification question", error)
            }
        }
    }
}
