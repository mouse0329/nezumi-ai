package com.nezumi_ai.data.inference

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PromptTemplateStore.lintJinjaTemplate] の軽量バリデーションの単体テスト。
 * 完全な構文解析は llama.cpp 側に任せ、ここでは if/for/変数展開の対応チェックのみを検証する。
 */
class PromptTemplateStoreJinjaLintTest {

    @Test
    fun `lint returns error for blank template`() {
        assertNotNull(PromptTemplateStore.lintJinjaTemplate(""))
        assertNotNull(PromptTemplateStore.lintJinjaTemplate("   "))
    }

    @Test
    fun `lint returns error for unbalanced variable braces`() {
        val err = PromptTemplateStore.lintJinjaTemplate("{{ message['role'] }")
        assertNotNull(err)
    }

    @Test
    fun `lint returns error for unbalanced if endif`() {
        val err = PromptTemplateStore.lintJinjaTemplate(
            "{% if add_generation_prompt %}<|assistant|>"
        )
        assertNotNull(err)
    }

    @Test
    fun `lint returns error for unbalanced for endfor`() {
        val err = PromptTemplateStore.lintJinjaTemplate(
            "{% for message in messages %}{{ message['content'] }}"
        )
        assertNotNull(err)
    }

    @Test
    fun `lint passes for well formed chatml template`() {
        val template = """
            {% for message in messages %}<|im_start|>{{ message['role'] }}
            {{ message['content'] }}<|im_end|>
            {% endfor %}{% if add_generation_prompt %}<|im_start|>assistant
            {% if enable_thinking %}<think>
            {% endif %}{% endif %}
        """.trimIndent()
        assertNull(PromptTemplateStore.lintJinjaTemplate(template))
    }

    @Test
    fun `lint passes for template with dash control syntax`() {
        // {%- ... -%} の whitespace 制御記法も許容すること。
        val template = "{%- for message in messages -%}{{ message['content'] }}{%- endfor -%}"
        assertNull(PromptTemplateStore.lintJinjaTemplate(template))
    }

    @Test
    fun `all builtin templates pass lint`() {
        // ビルトインテンプレートが全て lint を通過すること (Jinja 置換の回帰防止)。
        for (builtin in PromptTemplateStore.BUILTIN_TEMPLATES) {
            assertNull("builtin ${builtin.id} must pass lint", PromptTemplateStore.lintJinjaTemplate(builtin.template))
        }
    }
}
