package ru.ravel.rcrifprocessviewer.ui

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import ru.ravel.rcrifprocessviewer.config.AppConfig
import java.io.File


object SettingsWindow {

	fun show(owner: Window?, onSaved: (() -> Unit)? = null) {
		val stage = Stage().apply {
			title = "Настройки"
			initModality(Modality.WINDOW_MODAL)
			if (owner != null) initOwner(owner)
			isResizable = false
		}

		val ideaPathField = TextField(AppConfig.loadIdeaPath().orEmpty()).apply {
			promptText = "Папка установки IntelliJ IDEA"
		}
		HBox.setHgrow(ideaPathField, Priority.ALWAYS)

		val chooseIdeaFolderButton = Button("Выбрать папку").apply {
			setOnAction {
				val chooser = DirectoryChooser().apply {
					title = "Выберите папку IntelliJ IDEA"
					val current = ideaPathField.text
						?.trim()
						?.takeIf(String::isNotEmpty)
						?.let(::File)
					if (current?.isDirectory == true) initialDirectory = current
				}
				chooser.showDialog(stage)?.let { selected ->
					ideaPathField.text = selected.absolutePath
				}
			}
		}

		val pathRow = HBox(8.0, ideaPathField, chooseIdeaFolderButton).apply {
			alignment = Pos.CENTER_LEFT
		}

		val xsltSandboxPathField = TextField(AppConfig.loadXsltSandboxPath().orEmpty()).apply {
			promptText = "Исполняемый файл XSLTSandbox"
		}
		HBox.setHgrow(xsltSandboxPathField, Priority.ALWAYS)

		val chooseXsltSandboxButton = Button("Выбрать файл").apply {
			setOnAction {
				val chooser = FileChooser().apply {
					title = "Выберите XSLTSandbox"
					val current = xsltSandboxPathField.text
						?.trim()
						?.takeIf(String::isNotEmpty)
						?.let(::File)
					current?.parentFile?.takeIf(File::isDirectory)?.let { initialDirectory = it }
				}
				chooser.showOpenDialog(stage)?.let { selected ->
					xsltSandboxPathField.text = selected.absolutePath
				}
			}
		}

		val xsltSandboxRow = HBox(8.0, xsltSandboxPathField, chooseXsltSandboxButton).apply {
			alignment = Pos.CENTER_LEFT
		}

		val saveButton = Button("Сохранить").apply {
			isDefaultButton = true
			setOnAction {
				AppConfig.saveIdeaPath(ideaPathField.text)
				AppConfig.saveXsltSandboxPath(xsltSandboxPathField.text)
				onSaved?.invoke()
				stage.close()
			}
		}
		val cancelButton = Button("Отмена").apply {
			isCancelButton = true
			setOnAction { stage.close() }
		}
		val buttons = HBox(8.0, saveButton, cancelButton).apply {
			alignment = Pos.CENTER_RIGHT
		}

		val root = VBox(
			10.0,
			Label("Путь к IDEA:"),
			pathRow,
			Label("Путь к XSLT-sandbox:"),
			xsltSandboxRow,
			buttons,
		).apply {
			padding = Insets(16.0)
			prefWidth = 620.0
		}

		stage.scene = Scene(root)
		stage.showAndWait()
	}
}