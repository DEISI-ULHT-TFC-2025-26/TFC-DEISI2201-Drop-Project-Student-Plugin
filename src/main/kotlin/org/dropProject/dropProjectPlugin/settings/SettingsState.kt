package org.dropProject.dropProjectPlugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import javax.annotation.Nullable

@State(
    name = "com.tfc.ulht.dropProjectPlugin.SettingsState",
    storages = [Storage("DropProjectSettings.xml")]
)
class SettingsState : PersistentStateComponent<SettingsState> {

    var serverURL: String = "https://deisi.ulusofona.pt/drop-project"
    var llmServerURL: String = "https://api.openai.com/v1" // Novo campo para o servidor GenAI
    var username: String = ""
    var usernumber: String = ""
    var token: String = ""
    var openAiToken: String = ""
    var autoSendPrompt: Boolean = false
    var publicAssignments: MutableList<String> = mutableListOf()
    var sentenceList: MutableList<String> = mutableListOf()
    var dpMaxRequestsAllowed: Int = 3
    var dpRequestsMade: Int = 0
    var dpAllowCodeSubmission: Boolean = false

    companion object {
        fun getInstance(): SettingsState {
            return ApplicationManager.getApplication().getService(SettingsState::class.java)
        }
    }

    @Nullable
    override fun getState(): SettingsState {
        return this
    }

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun updateValues(userName: String = "", userNumber: String = "", token: String = "") {
        this.username = userName
        this.usernumber = userNumber
        this.token = token
    }

    fun addPublicAssignment(id: String) {
        publicAssignments.add(id)
    }

    fun removePublicAssignment(id: String) {
        publicAssignments.remove(id)
    }

    fun isPublicAssignment(id: String): Boolean {
        return publicAssignments.contains(id)
    }
}