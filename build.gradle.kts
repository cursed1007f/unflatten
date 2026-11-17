plugins {
    application
    java
}

group = "decompiler"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // ASM Tree API — парсинг байткода. commons/analysis/tree нужны на следующих этапах,
    // поэтому подключаем сразу весь набор, чтобы не трогать build-файл позднее.
    implementation("org.ow2.asm:asm:9.11")
    implementation("org.ow2.asm:asm-tree:9.11")
    implementation("org.ow2.asm:asm-analysis:9.11")
    implementation("org.ow2.asm:asm-commons:9.11")
    implementation("org.ow2.asm:asm-util:9.11")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "decompiler.cli.Main"
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "decompiler.gui.DecompilerGui"
    }
}

// FAT jar: один исполнительный jar со всеми зависимостями (ASM и т.д.).
// После сборки — запуск: java -jar build/libs/java-decompiler-0.1.0-all.jar
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "decompiler.gui.DecompilerGui"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

tasks.register<JavaExec>("runGui") {
    group = "application"
    description = "Запускает GUI декомпилятора"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "decompiler.gui.DecompilerGui"
}