plugins {
	kotlin("jvm") version "2.0.21"
	id("org.openjfx.javafxplugin") version "0.1.0"
	application
}

group = "ru.ravel"
version = "0.1.0"

repositories {
	mavenCentral()
}

dependencies {
	implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.2")
	implementation("org.xerial:sqlite-jdbc:3.45.3.0")
	implementation("org.postgresql:postgresql:42.7.10")
	testImplementation(kotlin("test-junit5"))
}

tasks.test {
	useJUnitPlatform()
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

tasks.jar {
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE

	from(sourceSets.main.get().output)

	from({
		configurations.runtimeClasspath.get()
			.filter { it.name.endsWith(".jar") && !it.name.startsWith("javafx-") }
			.map { zipTree(it) }
	})

	exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")

	manifest {
		attributes["Main-Class"] = "ru.ravel.rcrifprocessviewer.RCrifProcessViewerKt"
	}
}
