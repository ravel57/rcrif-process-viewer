package ru.ravel.rcrifprocessviewer.ui

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import ru.ravel.rcrifprocessviewer.config.AppConfig
import ru.ravel.rcrifprocessviewer.config.TraceDatabaseSettings
import java.io.File

/** Простое окно настроек viewer'а. */
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

		val databaseSettings = AppConfig.loadTraceDatabaseSettings()
		val databaseUrlField = TextField(databaseSettings.url).apply {
			promptText = "jdbc:postgresql://localhost:5432/ru_flow"
		}
		val databaseUserField = TextField(databaseSettings.user).apply {
			promptText = "Пользователь PostgreSQL"
		}
		val databasePasswordField = PasswordField().apply {
			text = databaseSettings.password
			promptText = "Пароль PostgreSQL"
		}

		val saveButton = Button("Сохранить").apply {
			isDefaultButton = true
			setOnAction {
				AppConfig.saveIdeaPath(ideaPathField.text)
				AppConfig.saveTraceDatabaseSettings(
					TraceDatabaseSettings(
						url = databaseUrlField.text.orEmpty(),
						user = databaseUserField.text.orEmpty(),
						password = databasePasswordField.text.orEmpty(),
					),
				)
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
			Label("PostgreSQL с трейсами RU Flow:"),
			databaseUrlField,
			databaseUserField,
			databasePasswordField,
			Label("Пустой JDBC URL включает демонстрационную SQLite."),
			buttons,
		).apply {
			padding = Insets(16.0)
			prefWidth = 620.0
		}

		stage.scene = Scene(root)
		stage.showAndWait()
	}
}
