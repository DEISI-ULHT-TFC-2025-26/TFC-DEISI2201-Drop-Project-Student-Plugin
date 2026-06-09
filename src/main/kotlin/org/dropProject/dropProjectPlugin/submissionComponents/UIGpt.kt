package org.dropProject.dropProjectPlugin.submissionComponents

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.CheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dropProject.dropProjectPlugin.gpt.ChatHtmlBuilder
import org.dropProject.dropProjectPlugin.gpt.GptInteraction
import org.dropProject.dropProjectPlugin.settings.SettingsState
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent

class UIGpt() {

    private var project = ProjectManager.getInstance().openProjects[0]
    private var gptInteraction = GptInteraction(project)
    private var textField = JBTextArea(4, 20)
    private var phrases = ArrayList<String>()
    private var sendButton = JButton()
    private var phraseComboBox = JComboBox(phrases.toArray())
    private var phraseComboPanel = JPanel()
    private val responseArea = JEditorPane()
    private var inputAndSubmitPanel = JPanel(GridBagLayout())
    private var uI: JBScrollPane = JBScrollPane()
    private var chatHtml = ChatHtmlBuilder()
    private var usefulButton = JButton("Useful", AllIcons.Ide.LikeSelected)
    private var notUsefulButton = JButton("Not Useful", AllIcons.Ide.DislikeSelected)
    // O botão global copyCodeButton foi removido daqui
    private var resetButton = JButton("Clear Chat", AllIcons.Actions.Refresh)
    private var askTwiceCheckBox = CheckBox("Ask for 2 Solutions")
    private var askTwice = false
    private var waitingForResponse = false
    private val ratingButtons = false

    init {
        //Linha puramente para testes para resetar sempre os pedidos ao LLM
        //SettingsState.getInstance().dpRequestsMade = 0
        // Configuração da fonte e margens do campo de texto
        textField.font = Font("Dialog", Font.PLAIN, 12)
        textField.margin = JBUI.insets(5)
        textField.lineWrap = true
        textField.wrapStyleWord = true
        textField.rows = 4

        // Texto de sugestão (Placeholder) em itálico
        textField.emptyText.text = "Insira aqui a sua prompt"
        textField.emptyText.setFont(Font("Dialog", Font.ITALIC, 12))

        textField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown) {
                        textField.append("\n")
                    } else if (!waitingForResponse) {
                        val settings = SettingsState.getInstance()
                        if (settings.dpRequestsMade < settings.dpMaxRequestsAllowed) {
                            e.consume()
                            sendPrompt()
                        } else {
                            e.consume()
                        }
                    }
                }
            }
        })

        textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateTextFieldSize()
            override fun removeUpdate(e: DocumentEvent) = updateTextFieldSize()
            override fun changedUpdate(e: DocumentEvent) = updateTextFieldSize()
        })

        // Configuração da área de mensagens (HTML) com Listener de Cópia
        responseArea.apply {
            contentType = "text/html"
            editorKit = HTMLEditorKitBuilder().build().also {
                it.styleSheet.addStyleSheet(chatHtml.getStyle())
            }
            isEditable = false
            foreground = JBColor.foreground()
            background = JBColor.background()
            isOpaque = true
            text = chatHtml.getHtmlChat()

            // NOVO: Listener para detectar o clique no link de "Copy" dentro do HTML
            responseArea.addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val description = e.description
                    if (description.startsWith("copy://")) {
                        // 1. Descodifica o URL (resolve espaços e símbolos básicos)
                        var codeToCopy = URLDecoder.decode(description.substring(7), StandardCharsets.UTF_8.name())

                        // 2. Resolve as entidades HTML (transforma &quot; em ", &lt; em <, etc.)
                        // Se o seu projeto não reconhecer o StringEscapeUtils, use o replace manual abaixo
                        codeToCopy = codeToCopy
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;", "&")

                        val stringSelection = StringSelection(codeToCopy)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(stringSelection, null)

                        JOptionPane.showMessageDialog(this, "Code copied to clipboard!", "Success", JOptionPane.INFORMATION_MESSAGE)
                    }
                }
            }

            UIUtil.doNotScrollToCaret(this)
            UIUtil.invokeLaterIfNeeded {
                revalidate()
                setCaretPosition(document.length)
            }
        }

        val settingsState = SettingsState.getInstance()
        phrases = ArrayList(settingsState.sentenceList)
        phrases.add(0, "")

        phraseComboPanel = createComboBoxPanel()

        usefulButton.addActionListener {
            gptInteraction.markLastResponseAs(true)
        }

        notUsefulButton.addActionListener {
            gptInteraction.markLastResponseAs(false)
        }

        // Botão de envio renomeado para GenAI
        sendButton = JButton("Ask GenAI")
        sendButton.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                val settings = SettingsState.getInstance()
                if (settings.dpRequestsMade < settings.dpMaxRequestsAllowed) {
                    sendPrompt()
                }
            }
        })

        // Listener para o botão de limpar chat
        resetButton.addActionListener {
            resetChat()
        }

        // --- PAINEL DE INPUT E BOTÕES (GRIDBAGLAYOUT) ---
        inputAndSubmitPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(3)

        // Row 1: Botões de Rating (se ativos)
        if(ratingButtons) {
            gbc.gridwidth = 1
            gbc.gridx = 0
            gbc.gridy = 1
            gbc.weightx = 0.5
            gbc.anchor = GridBagConstraints.CENTER
            gbc.fill = GridBagConstraints.BOTH
            inputAndSubmitPanel.add(usefulButton, gbc)

            gbc.gridx = 1
            gbc.gridy = 1
            inputAndSubmitPanel.add(notUsefulButton, gbc)
        }

        // Row 2: Label da caixa de texto
        val inputLabel = JLabel("Insira aqui a sua prompt:")
        inputLabel.font = inputLabel.font.deriveFont(Font.BOLD)
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        inputAndSubmitPanel.add(inputLabel, gbc)

        // Row 3: Caixa de Texto
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.gridwidth = 2
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        inputAndSubmitPanel.add(JBScrollPane(textField), gbc)

        // Row 4: Suffix Combo e Checkbox
        gbc.gridwidth = 1
        gbc.weightx = 0.5
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.fill = GridBagConstraints.CENTER
        inputAndSubmitPanel.add(phraseComboPanel, gbc)

        gbc.gridx = 1
        gbc.gridy = 4
        inputAndSubmitPanel.add(askTwiceCheckBox, gbc)

        // Row 5: Botão Ask GenAI
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.BOTH
        inputAndSubmitPanel.add(sendButton, gbc)

        // Row 6: Botão Clear Chat
        gbc.gridx = 0
        gbc.gridy = 6
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.BOTH
        inputAndSubmitPanel.add(resetButton, gbc)

        askTwiceCheckBox.addActionListener {
            askTwice = askTwiceCheckBox.isSelected
        }

        // Ajuste da altura do painel inferior (reduzido um pouco pois tiramos o Copy Code global)
        inputAndSubmitPanel.preferredSize = Dimension(600, 220)

        // --- MONTAGEM FINAL DA ESTRUTURA ---
        val mainViewPanel = JPanel(BorderLayout())
        val chatScrollPane = JBScrollPane(responseArea)
        chatScrollPane.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        chatScrollPane.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_ALWAYS
        chatScrollPane.viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE

        mainViewPanel.add(chatScrollPane, BorderLayout.CENTER)
        mainViewPanel.add(inputAndSubmitPanel, BorderLayout.SOUTH)

        uI = JBScrollPane(mainViewPanel).apply {
            border = null
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
        }

        checkDropProjectLimits()
    }

    private fun updateTextFieldSize() {
        val prefSize = textField.preferredSize
        textField.setSize(prefSize.width, prefSize.height)
        textField.revalidate()
    }

    fun buildComponents(): JBScrollPane {
        return uI
    }

    private fun createComboBoxPanel(): JPanel {
        phraseComboBox = JComboBox(phrases.toTypedArray())
        val label = JLabel("Suffix with:")
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.add(label)
        panel.add(Box.createHorizontalStrut(5))
        panel.add(phraseComboBox)
        label.alignmentY = Component.CENTER_ALIGNMENT
        phraseComboBox.alignmentY = Component.CENTER_ALIGNMENT
        panel.maximumSize = panel.preferredSize
        return panel
    }

    private fun escapeKotlinSpecialCharacters(input: String): String {
        return input.replace("[\\\\$\"\\n\\r\\t\b\\u000c']".toRegex(), "")
    }

    fun addToPrompt(text : String) {
        textField.text += text
    }

    // 🛡️ Nova função para verificar os limites e trancar os componentes gráficos da ToolWindow
    private fun checkDropProjectLimits() {
        val settings = SettingsState.getInstance()
        val hasTokensLeft = settings.dpRequestsMade < settings.dpMaxRequestsAllowed

        SwingUtilities.invokeLater {
            sendButton.isEnabled = hasTokensLeft
            textField.isEnabled = hasTokensLeft

            if (!hasTokensLeft) {
                sendButton.text = "Ask GenAI (Limit Reached)"
            } else {
                sendButton.text = "Ask GenAI"
            }
        }
    }

    fun sendPrompt() {
        val scope = CoroutineScope(Dispatchers.Default)

        if (textField.text != null && textField.text != "") {
            sendButton.isEnabled = false
            waitingForResponse = true

            val selectedPhrase = phraseComboBox.selectedItem as String
            val message = "${textField.text} $selectedPhrase"

            val escapedMessage = escapeKotlinSpecialCharacters(message)

            textField.text = ""

            scope.launch(Dispatchers.Default) {
                gptInteraction.addPromptMessage(escapedMessage)
                chatHtml.append("User", escapedMessage, true)
                gptInteraction.logMessageUser(escapedMessage)
                updateChatScreen()

                val response = gptInteraction.executePrompt(escapedMessage)
                chatHtml.append("GenAI", response, false)
                updateChatScreen()

                if (askTwice) {
                    val altResponse = gptInteraction.executePrompt(escapedMessage)
                    chatHtml.append("GenAI", altResponse, false)
                    updateChatScreen()

                    SwingUtilities.invokeLater {
                        openDiffViewer(response, altResponse)
                    }
                }

                SwingUtilities.invokeLater {
                    val settings = SettingsState.getInstance()
                    if (settings.dpRequestsMade < settings.dpMaxRequestsAllowed) {
                        sendButton.isEnabled = true
                        waitingForResponse = false
                    }
                }
            }
        }
    }

    private fun openDiffViewer(response1: String, response2: String) {
        val content1 = DiffContentFactory.getInstance().create(project, response1)
        val content2 = DiffContentFactory.getInstance().create(project, response2)
        val request = SimpleDiffRequest("Response Comparison", content1, content2, "Original Response", "Alternative Response")
        DiffManager.getInstance().showDiff(project, request)
    }

    fun updatePhrases(sentenceList: MutableList<String>) {
        phrases = sentenceList as ArrayList<String>

        // Garante que a opção vazia continua no início
        if (phrases.isEmpty() || phrases[0] != "") {
            phrases.add(0, "")
        }

        phraseComboBox.removeAllItems() // Limpa os itens atuais do JComboBox

        for (phrase in phrases) {
            phraseComboBox.addItem(phrase) // Adiciona as novas frases das definições
        }
    }

    private fun updateChatScreen() {
        responseArea.text = chatHtml.getHtmlChat()

        checkDropProjectLimits()

        SwingUtilities.invokeLater {
            uI.verticalScrollBar.value = uI.verticalScrollBar.maximum
        }
    }

    private fun resetChat() {
        chatHtml.reset()
        gptInteraction.reset()
        updateChatScreen()
    }

    companion object {
        var instance1 : UIGpt? = null
        fun getInstance() : UIGpt {
            if(instance1 == null) {
                instance1 = UIGpt()
            }
            return instance1!!
        }
    }
}