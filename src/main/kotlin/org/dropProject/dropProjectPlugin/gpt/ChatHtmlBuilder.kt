package org.dropProject.dropProjectPlugin.gpt

import com.intellij.ui.JBColor
import com.intellij.util.ui.StyleSheetUtil
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import javax.swing.text.html.StyleSheet
import java.util.regex.Matcher


class ChatHtmlBuilder {
    private var content = StringBuilder("<table>")

    fun getHtmlChat(): String {
        return "$content</table>\n"
    }

    fun append(user: String, message: String, isUser: Boolean) {
        content.append("<tr>")

        if (isUser) {
            content.append("<td class=\"user-column\">$user: </td>\n")
            content.append("<td class=\"message-column\">${converMkdownToHtml(message)}</td>\n")
        } else {
            content.append("<td class=\"chatgpt-column\">$user: </td>\n")
            content.append("<td class=\"message-column\">${converMkdownToHtml(message)}</td>\n")
        }

        content.append("</tr>")
    }

    private fun converMkdownToHtml(mkdownText: String): String {
        val parser = Parser.builder().build()
        val document = parser.parse(mkdownText)
        val htmlRenderer = HtmlRenderer.builder().build()
        var htmlResponse = htmlRenderer.render(document)

        // Se for uma resposta da IA, adicionamos os botões de cópia nos blocos de código
        htmlResponse = addCopyButtonsToCodeBlocks(htmlResponse)

        return htmlResponse
    }

    /**
     * Procura por blocos <code>...</code> no HTML e adiciona um link de cópia.
     */
    private fun addCopyButtonsToCodeBlocks(html: String): String {
        // ALTERAÇÃO: A Regex agora procura pelo par <pre><code> para garantir que é um bloco grande
        val pattern = Pattern.compile("<pre><code.*?>(.*?)</code></pre>", Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        val sb = StringBuffer()

        while (matcher.find()) {
            val rawCode = matcher.group(1)

            // Limpeza do código para o link (remover tags HTML que o flexmark possa ter inserido para syntax highlighting)
            val cleanCode = rawCode.replace("<[^>]*>".toRegex(), "")

            // Encode para o link copy://
            val encodedCode = URLEncoder.encode(cleanCode, StandardCharsets.UTF_8.name())

            // Montagem do substituto: Mantemos o <pre><code> original e adicionamos o botão por baixo
            val replacement = """
                <pre><code>$rawCode</code></pre>
                <div class="copy-container">
                    <a href="copy://$encodedCode">Copy Code</a>
                </div>
            """.trimIndent()

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    fun getStyle(): StyleSheet = StyleSheetUtil.loadStyleSheet("""
    * {
        margin: 0;
        padding: 0;
        font-family: Consolas, 'Courier New', monospace;
        font-size: 10px;
        word-wrap: break-word;
    }
    table {
        width: 95%;
        border-collapse: collapse;
    }
    p {
        word-wrap: break-word;
        margin-bottom: 5px;
    }
    
    code {
        color: ${getCodeColor()};
        background-color: ${getCodeBackgroundColor()};
        padding: 2px;
    }

    /* Estilo para o contentor do link de cópia */
    .copy-container {
        text-align: right;
        margin-top: 2px;
        margin-bottom: 10px;
    }

    .copy-container a {
        color: #589df6;
        text-decoration: none;
        font-weight: bold;
        font-size: 9px;
        border: 1px solid #589df6;
        padding: 2px 5px;
        border-radius: 3px;
    }
    
    td, th {
        padding: 8px;
        word-wrap: break-word;
        max-width: 400px;
        text-align: left;
        vertical-align: top;
        border-bottom: 1px solid #ccc;
    }
    .user-column {
        width: 4%;
        font-weight: bold;
        color: ${getUserColor()};
    }
    .message-column {
        width: 96%;
    }
    .chatgpt-column {
        font-weight: bold;
        color: ${getGPTColor()};
    }
    """.trimIndent())

    private fun getCodeColor(): String {
        if (isCurrentThemeDark()) {
            return "rgb(200, 200, 255)"
        }
        return "rgb(138, 43, 226)"
    }

    private fun getCodeBackgroundColor(): String {
        if (isCurrentThemeDark()) {
            return "rgb(40, 40, 40)"
        }
        return "rgb(240, 240, 240)"
    }

    private fun getUserColor(): String {
        if (isCurrentThemeDark()) {
            return "rgb(50, 255, 50)"
        }
        return "rgb(0, 230, 0)"
    }

    private fun getGPTColor(): String {
        if (isCurrentThemeDark()) {
            return "rgb(0, 100, 255)"
        }
        return "rgb(0, 0, 240)"
    }

    private fun isCurrentThemeDark(): Boolean {
        val backgroundColor = JBColor.background()
        val red = backgroundColor.red
        val green = backgroundColor.green
        val blue = backgroundColor.blue
        val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
        return luminance < 128
    }

    fun reset() {
        content = StringBuilder("<table>")
    }
}