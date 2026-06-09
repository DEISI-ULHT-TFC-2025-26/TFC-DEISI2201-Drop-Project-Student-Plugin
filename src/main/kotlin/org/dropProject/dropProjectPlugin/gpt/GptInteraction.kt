package org.dropProject.dropProjectPlugin.gpt

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.squareup.moshi.Moshi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.dropProject.dropProjectPlugin.DefaultNotification
import org.dropProject.dropProjectPlugin.settings.SettingsState
import java.io.File
import java.nio.file.FileSystems
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


class GptInteraction(var project: Project) {
    private val model = "gemma-4-e4b"
    private val separator = FileSystems.getDefault().separator
    //private val logFileDirectory = "${System.getProperty("user.home")}${separator}Documents${separator}Drop Project Plugin${separator}"
    private val logFileDirectory = project.let { FileEditorManager.getInstance(it).project.basePath.toString() }
    private val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")
    private var dateTime = Date()
    private var logFile = File("${logFileDirectory}${separator}chat_logs${separator}chat_log_${formatter.format(dateTime)}.json")
    private var responseLog = ArrayList<GPTResponse>()
    private var chatLog = ArrayList<Message>()
    private var chatToSave = ArrayList<LogMessage>()
    private var messages = ArrayList<Message>()

    init {
        createPathIfDoesntExist()
    }

    private fun createPathIfDoesntExist() {
        val logFileParent = logFile.parentFile
        if (!logFileParent.exists()) {
            logFileParent.mkdirs() // Creating the parent directories if they don't exist
        }
        if (!logFile.exists()) {
            logFile.createNewFile() // Creating the target file if it doesn't exist
        }
    }

    // 🔍 Função auxiliar para ler o ficheiro de contexto GenAI.md na raiz do projeto
    private fun getProjectContext(): String {
        try {
            val basePath = project.basePath ?: return ""
            val contextFile = File(basePath, "GenAI.md")

            if (contextFile.exists() && contextFile.isFile) {
                val content = contextFile.readText(Charsets.UTF_8).trim()
                if (content.isNotEmpty()) {
                    return content
                }
            }
        } catch (e: Exception) {
            println("Erro ao ler o ficheiro GenAI.md: ${e.message}")
        }
        return ""
    }
    fun executePrompt(prompt: String): String {
        addPromptMessage(prompt) // adiciona a mensagem do user à lista

        val chatGptResponse = processPrompt()

        chatLog.add(Message("system", chatGptResponse))

        if (chatGptResponse.contains("Error")) {
            return chatGptResponse
        }

        return responseLog.last().choices.first().message.content
    }

    private fun processPrompt(): String {

        val settingsState = SettingsState.getInstance()
        val apiKey = "sk-8r9FghcFF5UyzzJu37mYHA"

        if (apiKey == "") {
            DefaultNotification.notify(project, "No API key set")
            return "Error: No API key set"
        }

        if (settingsState.dpRequestsMade >= settingsState.dpMaxRequestsAllowed) {
            DefaultNotification.notify(project, "Drop Project: Request limit reached for this assignment.")
            return "Error: Request limit reached for this assignment."
        }

        var apiUrl = "https://modelos.ai.ulusofona.pt/v1/chat/completions"

        val finalMessages = ArrayList<Message>()

        if (!settingsState.dpAllowCodeSubmission) {
            val pedagogicalPrompt = """
                [RESTRIÇÃO ACADÉMICA CRÍTICA]
                Estás inserido num ambiente de avaliação de programação (Drop Project). 
                É TERMINANTEMENTE PROIBIDO fornecer código pronto em linguagem Java, Kotlin ou qualquer outra sintaxe funcional.
                Não ignores esta regra. Se o utilizador pedir código ou resoluções, deves:
                1. Explicar a lógica do algoritmo passo a passo usando linguagem natural (Português).
                2. Se necessário, ilustrar a estrutura EXCLUSIVAMENTE em formato de PSEUDO-CÓDIGO puramente conceptual (sem usar sintaxe real de Java, sem chaves, sem declarações de tipos estritas).
                3. Nunca utilizes blocos de código markdown com sintaxe formal (ex: ```java).
            """.trimIndent()

            finalMessages.add(Message("system", pedagogicalPrompt))
        }

        val contextText = getProjectContext()
        if (contextText.isNotEmpty()) {
            finalMessages.add(Message("system", contextText))
        }

        finalMessages.addAll(messages)

        val messagesJson = finalMessages.joinToString(",") {
            val escapedContent = it.content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            """
            {
                "role": "${it.role}",
                "content": "$escapedContent"
            }
            """
        }

        val requestBody =
            """
            {
                "model": "$model",
                "messages": [$messagesJson]
            }
            """.trimIndent()

        val client = getUnsafeOkHttpClient()

        val builder = Request.Builder()
            .url(apiUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")

        print("000" + builder + "\n")

        if (apiKey.startsWith("sk-proj-")) {
            builder.addHeader("OpenAI-Project", "proj_8sQTuo7LxVtQB41bRrFEZiCc")
            print("cuidado" + "\n")
        }

        val request = builder
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        print("001" + request + "\n")

        try {
            val response = client.newCall(request).execute()

            print("002" + response)

            if (!response.isSuccessful) {
                val json = response.body?.string()
                val moshi = Moshi.Builder().build()
                val adapter = moshi.adapter(ErrorResponse::class.java)
                val myResponse = adapter.fromJson(json!!) ?: return "didnt work"

                DefaultNotification.notify(project, "Response unsuccseessful, no tokens")
                logMessageGpt(myResponse.error.message)
                return "Error code: {${myResponse.error.code}}"
            }

            val json = response.body?.string()
            val moshi = Moshi.Builder().build()
            val adapter = moshi.adapter(GPTResponse::class.java)
            val myResponse = adapter.fromJson(json!!) ?: return ""

            client.connectionPool.evictAll()

            var rawPayload = myResponse.choices.first().message.content

            // Se o assignment proibir código, verifica se a resposta contém blocos de código markdown E termos de Java
            if (!settingsState.dpAllowCodeSubmission) {
                val containsCodeBlocks = rawPayload.contains("```java") || rawPayload.contains("```")
                val containsJavaKeywords = rawPayload.contains("public class") ||
                        rawPayload.contains("System.out.print") ||
                        rawPayload.contains("public static void main") ||
                        rawPayload.contains("int ") ||
                        rawPayload.contains("String ")

                if (containsCodeBlocks && containsJavaKeywords) {
                    // O modelo falhou e tentou dar código Java. O Regex limpa os blocos estruturados.
                    rawPayload = """
                        |⚠️ [Nota do Drop Project: Esta atividade não permite a exibição de código Java direto. A resposta foi filtrada pelo plugin.]
                        |
                        |${rawPayload.replace("```java[\\s\\S]*?```".toRegex(), "[Bloco de código Java removido por restrição do enunciado - Consulta a explicação conceptual acima]").replace("```[\\s\\S]*?```".toRegex(), "[Bloco de código filtrado]")}
                    """.trimMargin()
                }
            }

            // Garante que o objeto interno da resposta guarda a string tratada/filtrada
            myResponse.choices.first().message.content = rawPayload

            responseLog.add(myResponse)
            logMessageGpt(rawPayload)

            settingsState.dpRequestsMade++

            return rawPayload

        } catch (exception : Exception) {
            return "Erro desconhecido"
        }
    }

    private fun logMessageGpt(message: String) {
        val logMessage = LogMessage("ChatGPT", message.trim(), java.time.LocalDateTime.now(), model, null)
        chatToSave.add(logMessage)
        updateLogFile()
    }

    public fun logMessageUser(prompt: String) {
        val logMessage = LogMessage("user", prompt.trim(), java.time.LocalDateTime.now(), null, null)
        chatToSave.add(logMessage)
        updateLogFile()
    }

    private fun updateLogFile() {
        logFile.delete()
        logFile.createNewFile()
        for (message in chatToSave) {
            logFile.appendText(message.toString() + "\n")
        }

        logFile.delete()
        logFile.createNewFile()

        logFile.appendText("{\n")
        logFile.appendText("\"value\": [\n")

        var i = 0
        val nrEntries = chatToSave.size

        for (message in chatToSave) {
            var commaIfNeeded = ""
            if(i < nrEntries - 1) {
                commaIfNeeded = ", "
            }
            logFile.appendText(message.writeToJSON() + commaIfNeeded + "\n")
            i++
        }

        logFile.appendText("]\n")
        logFile.appendText("}\n")
    }

    fun addPromptMessage(prompt: String) {
        val message = Message("user", prompt)
        messages.add(message)
        chatLog.add(message)
    }

    fun getChatLog(): String {
        var log = ""

        for (message in chatLog) {
            if (message.role == "user")
            {
                log += "User: " + message.content + "\n"
            } else {
                log += "ChatGPT: " + message.content + "\n"
            }
        }

        return log
    }

    fun getChatLogHtml(): String {
        var log = ""

        for (message in chatLog) {
            if (message.role == "user")
            {
                log += "User: " + message.content + "<br><br>"
            } else {
                log += "ChatGPT: " + message.content + "<br><br>"
            }
        }

        log.removeSuffix("<br><br>")

        return log
    }

    fun getLastBlockOfCode(): String? {
        val codeBlockDelimiter = "```"

        if (!chatToSave.last().isFromGPT()) {
            return null
        }

        try {
            var messageContent = chatToSave.last().getContent()

            var startIndex = messageContent.indexOf(codeBlockDelimiter)
            messageContent = messageContent.substring(startIndex, messageContent.length)


            startIndex = messageContent.indexOf("\n")
            messageContent = messageContent.substring(startIndex, messageContent.length)

            val endIndex = messageContent.indexOf(codeBlockDelimiter)

            return messageContent.substring(0, endIndex)

        } catch (e: Exception) {
            println("IDK some error")
            return null
        }
    }

    fun markLastResponseAs(useful: Boolean) {
        for (message in chatToSave.reversed()) {
            if (!message.isFromGPT()) {
                break
            }
            message.markAs(useful)
        }
        updateLogFile()
    }

    fun reset() {
        dateTime = Date()
        logFile = File("${logFileDirectory}${separator}chat_logs${separator}chat_log_${formatter.format(dateTime)}.txt")
        createPathIfDoesntExist()

        responseLog = ArrayList<GPTResponse>()
        chatLog = ArrayList<Message>()
        chatToSave = ArrayList<LogMessage>()
        messages = ArrayList<Message>()
    }

    fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            val sslSocketFactory = sslContext.socketFactory

            val builder = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }

            return builder.build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}