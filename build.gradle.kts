plugins {
	kotlin("jvm") version "2.0.21"
	id("org.openjfx.javafxplugin") version "0.1.0"
	application
}

group = "ru.ravel"
version = "1.0.0"

repositories {
	mavenCentral()
}

dependencies {
	implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.2")
	// БД подключается здесь же, когда будет известен драйвер:
	// runtimeOnly("org.postgresql:postgresql:42.7.4")
}

javafx {
	version = "21.0.4"
	modules = listOf("javafx.controls")
}

kotlin {
	jvmToolchain(21)
}

application {
	mainModule.set("ru.ravel.rcrifprocessviewer")
	mainClass.set("ru.ravel.rcrifprocessviewer.RCrifProcessViewerKt")
}
