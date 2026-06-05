package org.dropProject.dropProjectPlugin.gpt

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.Messages
import org.dropProject.dropProjectPlugin.settings.SettingsState
import org.dropProject.dropProjectPlugin.submissionComponents.UIGpt
import org.jetbrains.annotations.NotNull

class AskGenAiGroup : ActionGroup() {
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val settings = SettingsState.getInstance()
        val prefixes = settings.sentenceList

        // Se não houver prefixos configurados, mostra uma ação padrão ou vazia
        if (prefixes.isEmpty()) {
            return arrayOf(object : AnAction("No prefixes configured") {
                override fun actionPerformed(e: AnActionEvent) {}
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = false }
            })
        }

        // Cria uma sub-ação para cada prefixo que o utilizador configurou nas Settings
        return prefixes.map { prefix ->
            SendWithPrefixAction(prefix)
        }.toTypedArray()
    }
}

class SendWithPrefixAction(private val prefix: String) : AnAction(prefix) {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText

        if (!selectedText.isNullOrEmpty()) {
            val uiGPT = UIGpt.getInstance()

            val finalPrompt = """
                |$prefix
                |
                |```
                |$selectedText
                |
        """.trimMargin()

            uiGPT.addToPrompt(finalPrompt)

            val settingsState = SettingsState.getInstance()
            if (settingsState.autoSendPrompt) {
                uiGPT.sendPrompt()
            }
        } else {
            Messages.showInfoMessage("No text selected", "Ask GenAI to...")
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor?.selectionModel?.hasSelection() ?: false
    }
}

class SendToGptEditor : AnAction() {
    override fun actionPerformed(@NotNull e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText

        if (selectedText != null) {
            val uiGPT = UIGpt.getInstance()
            uiGPT.addToPrompt(selectedText)

            val settingsState = SettingsState.getInstance()
            if (settingsState.autoSendPrompt) {
                uiGPT.sendPrompt()
            }
        } else {
            Messages.showInfoMessage("No text selected", "Send to GenAI")
        }
    }
}

class SendToGptConsole : AnAction() {
    override fun actionPerformed(@NotNull e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.caretModel.currentCaret.selectedText

        if (selectedText != null) {
            val uiGPT = UIGpt.getInstance()
            uiGPT.addToPrompt(selectedText)

            val settingsState = SettingsState.getInstance()
            if (settingsState.autoSendPrompt) {
                uiGPT.sendPrompt()
            }
        } else {
            Messages.showInfoMessage("No text selected", "Send to GenAI")
        }
    }
}