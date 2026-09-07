package io.github.zvensmoluya.tavernplayer.conversation.ejs

import io.github.zvensmoluya.tavernplayer.conversation.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

object EjsRuntimeContract {
    fun request(template: String) = EjsTemplateRequest("book:template", template,
        Json.parseToJsonElement("""{"stat_data":{"score":2,"inventory":{"tea":{"quantity":3}},"outfit":{}}}""").jsonObject,
        listOf(EjsHistoryMessage("user", "Tea please."), EjsHistoryMessage("assistant", "A coat."), EjsHistoryMessage("user", "Continue.")))

    suspend fun verify(bundle: String) {
        val runtime = QuickJsEjsRuntime(loadBundle = { bundle })
        val input = request("""
            |<%_ const bag = getvar('stat_data.inventory'); _%>
            |<%_ if (bag.tea.quantity === 3 && Object.keys(getvar('stat_data.outfit')).length === 0) { _%>
            |<%= await Promise.resolve('<tea>') %><% print(getvar('missing', {defaults: '!'})) %>
            |<%_ } else { _%>wrong<%_ } _%>
        """.trimMargin())
        val actual = runtime.render(input)
        check(actual == "<tea>!\n") { "Unexpected whitespace: ${kotlinx.serialization.json.JsonPrimitive(actual)}" }
        check(runtime.render(request("<%= matchChatMessages('Tea', {start:-2,end:0,role:'user'}) %>")) == "false")
        check(runtime.render(request("<%= matchChatMessages('T.a', {start:-3}) %>")) == "true")
        check(runtime.render(request("<%= getChatMessages(-2,'user').join('|') %>")) == "Tea please.|Continue.")
        runtime.render(request("<% getvar('stat_data.inventory').tea.quantity = 500; globalThis.leaked = true; %>"))
        check(runtime.render(request("<%= getvar('stat_data.inventory').tea.quantity %>|<%= typeof leaked %>")) == "3|undefined")
        check(input.variables["stat_data"]!!.jsonObject["score"].toString() == "2")
    }
}
