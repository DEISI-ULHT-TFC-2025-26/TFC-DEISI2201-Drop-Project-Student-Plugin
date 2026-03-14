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
    private val model = "gpt-4-turbo"
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
        val apiKey = "Insere a Key"

        if (apiKey == "") {
            DefaultNotification.notify(project, "No API key set")
            return "Error: No API key set"
        }

        var apiUrl = "Insere o URL"

        //apiUrl = "https://api.openai.com/v1/completions"

        val messagesJson = messages.joinToString(",") {
            """
            {
                "role": "${it.role}",
                "content": "${it.content}"
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

// 🔧 Suporte para novas chaves 'sk-proj-'
        if (apiKey.startsWith("sk-proj-")) {
            // Podes deixar "default" ou usar o ID real do teu projeto (ex: proj_abc123)
            builder.addHeader("OpenAI-Project", "proj_8sQTuo7LxVtQB41bRrFEZiCc")

            print("cuidado" + "\n")
        }

        val request = builder
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        print("001" + request + "\n")

        try {

            // okhttp3.Request$Builder@728eb99d001Request{method=POST, url=https://modelos.ai.ulusofona.pt/v1/completions, headers=[Content-Type:application/json, Authorization:Bearer sk-Oo32-A30q8CMaEMIzXG3Fg]}Exception in thread "DefaultDispatcher-worker-1" java.lang.NoClassDefFoundError: Could not initialize class kotlinx.coroutines.CoroutineExceptionHandlerImplKt
            //	at kotlinx.coroutines.CoroutineExceptionHandlerKt.handleCoroutineException(CoroutineExceptionHandler.kt:33)
            //	at kotlinx.coroutines.DispatchedTask.handleFatalException(DispatchedTask.kt:146)
            //	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:115)
            //	at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:571)
            //	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:750)
            //	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:678)
            //	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:665)
            val response = client.newCall(request).execute()

            print("002" + response)

            //println("res0: $response")

            if (!response.isSuccessful) {
                val json = response.body?.string()
                val moshi = Moshi.Builder().build()
                val adapter = moshi.adapter(ErrorResponse::class.java)
                //println(json)
                val myResponse = adapter.fromJson(json!!) ?: return "didnt work"

                DefaultNotification.notify(project, "Response unsuccseessful, no tokens")

                logMessageGpt(myResponse.error.message)

                return "Error code: {${myResponse.error.code}}"
            }

            //println("res1: $response")

            val json = response.body?.string()
            val moshi = Moshi.Builder().build()
            val adapter = moshi.adapter(GPTResponse::class.java)
            //println(json)
            val myResponse = adapter.fromJson(json!!) ?: return ""

            client.connectionPool.evictAll()

            responseLog.add(myResponse)

            logMessageGpt(myResponse.choices.first().message.content)

            return myResponse.choices.first().message.content

        } catch (exception : Exception) {
            //mostrar uma notificação a dizer que o chatgpt não respondeu
            return "Erro desconhecido"
        }
    }

    private fun logMessageGpt(message: String) {
        //println(logFile.absolutePath)
        /*
        try{
            logFile.appendText(
                "Author: ChatGPT" + "\n" +
                        "Model: $model\n" +
                        "DateTime: ${java.time.LocalDateTime.now()}\n" +
                        "Message: $message\n\n"
            )
        } catch (exception : Exception){
            println("Couldn't write file")
        }
        */
        val logMessage = LogMessage("ChatGPT", message.trim(), java.time.LocalDateTime.now(), model, null)
        chatToSave.add(logMessage)

        updateLogFile()
    }

    public fun logMessageUser(prompt: String) {
        //println(logFile.absolutePath)
        /*
        try {
            logFile.appendText(
                "Author: User" + "\n" +
                        "DateTime: ${java.time.LocalDateTime.now()}\n" +
                        "Message: $prompt\n\n"
            )
        } catch (exception : Exception){
            println("Couldn't write file")
        }
        */

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
        //Change to a new log file
        dateTime = Date()
        logFile = File("${logFileDirectory}${separator}chat_logs${separator}chat_log_${formatter.format(dateTime)}.txt")
        createPathIfDoesntExist()

        //Reset Data structure
        responseLog = ArrayList<GPTResponse>()
        chatLog = ArrayList<Message>()
        chatToSave = ArrayList<LogMessage>()
        messages = ArrayList<Message>()
    }

    fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory

            /*
            val loggingInterceptor = HttpLoggingInterceptor { message ->
                LOG.info(message)
            }.apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
             */

            val builder = OkHttpClient.Builder()
//               .addInterceptor(loggingInterceptor)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }  // Skip hostname verification

            return builder.build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

}